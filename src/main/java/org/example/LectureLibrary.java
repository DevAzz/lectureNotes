package org.example;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class LectureLibrary implements AutoCloseable {
    static final int DEFAULT_NOTE_TIME_LIMIT_MINUTES = 60;
    static final String DEFAULT_WHISPER_LANGUAGE = "ru";

    record Subject(long id, String name, String description, int lectures, int notes, int transcripts) {
        @Override public String toString() { return name; }
    }

    record Profile(long id, String name, String baseUrl, List<String> modelIds, boolean cloud, int contextTokens,
                   int answerTokens, int timeoutSeconds, boolean isDefault, boolean hasSecret) {
        @Override public String toString() { return name; }

        LocalProcessing.Llm llm(String modelId, String secret) {
            return new LocalProcessing.Llm(baseUrl, modelId, secret == null ? "" : secret, answerTokens,
                    timeoutSeconds, cloud);
        }
    }

    record Stats(int subjects, int lectures, int transcripts, int notes, int pendingCleanup) { }

    record Lecture(String id, long subjectId, String subject, String title, String topic,
                   String focus, Instant created, String state, boolean hasNote, boolean audioRemoved) {
        boolean capturing() { return state.equals("RECORDING") || state.equals("STOPPING"); }
        boolean canRecord() { return state.equals("DRAFT") || state.equals("EMPTY"); }
        boolean hasAudio() { return !audioRemoved && (state.equals("COMPLETE") || state.equals("PARTIAL")); }
        String label() { return subject + " · " + title; }
    }

    enum Kind { CARD, SEGMENT, NOTE }
    enum Sort { DATE, TITLE, RELEVANCE }
    record Filters(Long subjectId, Instant since, Instant before, Boolean hasNote, String state, Sort sort) { }
    record Hit(Lecture lecture, Kind kind, int ordinal, long startMillis, String excerpt, RussianSearch.Match match) { }
    record Documents(Long transcriptId, List<LocalProcessing.Segment> segments, Note note, String readingText) { }
    record LiveSegment(long id, LocalProcessing.Segment segment) {
        @Override public String toString() { return segment.toString(); }
    }
    record Note(Long transcriptId, String markdown) { }
    record NoteRevision(long id, Instant created, String source) { }

    private static final String LECTURE_COLUMNS = """
            l.id, l.subject_id, s.name, l.title, l.topic, l.focus, l.created_at, l.state,
            l.current_note IS NOT NULL, l.audio_removed
            """.strip();

    private final Path recordings;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Connection db;
    private final RussianSearch search = new RussianSearch();

    LectureLibrary(Path data) throws IOException, SQLException {
        recordings = Files.createDirectories(data.resolve("recordings")).toAbsolutePath().normalize();
        lockChannel = FileChannel.open(data.resolve("library.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        Connection connection = null;
        try {
            try {
                lock = lockChannel.tryLock();
            } catch (OverlappingFileLockException error) {
                throw new IOException("Библиотека уже открыта в другом экземпляре.", error);
            }
            if (lock == null) throw new IOException("Библиотека уже открыта в другом экземпляре.");
            connection = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("library.sqlite"));
            db = connection;
            try (var statement = db.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
            }
            migrate();
        } catch (IOException | SQLException | RuntimeException error) {
            if (connection != null) {
                try { connection.close(); } catch (SQLException failure) { error.addSuppressed(failure); }
            }
            try { lockChannel.close(); } catch (IOException failure) { error.addSuppressed(failure); }
            search.close();
            throw error;
        }
    }

    static void checkDriver() throws SQLException {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = db.createStatement()) {
            statement.execute("CREATE VIRTUAL TABLE startup_check USING fts5(text)");
        }
        try (var tokenizer = new RussianSearch()) {
            tokenizer.query("Расчёт");
        } catch (IOException error) {
            throw new SQLException("Не удалось запустить анализатор текста.", error);
        }
    }

    private void migrate() throws SQLException, IOException {
        int version;
        try (var statement = db.createStatement(); var rows = statement.executeQuery("PRAGMA user_version")) {
            rows.next();
            version = rows.getInt(1);
        }
        if (version == LibrarySchema.VERSION) return;
        if (version < 0 || version > LibrarySchema.VERSION) {
            throw new IOException("Библиотека создана более новой версией приложения.");
        }
        transaction(() -> {
            try (var statement = db.createStatement()) {
                if (version == 0) for (String sql : LibrarySchema.V1) statement.execute(sql);
                if (version < 2) statement.execute(LibrarySchema.V2);
                if (version < 3) for (String sql : LibrarySchema.V3) statement.execute(sql);
                if (version < 4) for (String sql : LibrarySchema.V4) statement.execute(sql);
                if (version < 5) for (String sql : LibrarySchema.V5) statement.execute(sql);
                if (version < 6) {
                    for (String sql : LibrarySchema.V6) statement.execute(sql);
                    seedDefaultProfile();
                }
                if (version < 7) statement.execute("ALTER TABLE transcript ADD COLUMN reading_text TEXT");
                if (version < 8) for (String sql : LibrarySchema.V8) statement.execute(sql);
                if (version == 0) importPrototype();
                statement.execute("PRAGMA user_version = " + LibrarySchema.VERSION);
            }
            return null;
        });
    }

    /** Пользователь ещё не создавал профилей — предлагаем тот же локальный API, что и раньше. */
    private void seedDefaultProfile() throws SQLException {
        try (var rows = db.createStatement().executeQuery("SELECT COUNT(*) FROM model_profile")) {
            if (rows.next() && rows.getInt(1) > 0) return;
        }
        update("""
                INSERT INTO model_profile(name, name_key, base_url, model_id, timeout_seconds, is_default)
                VALUES (?,?,?,?,?,1)
                """, "Локальная модель", RussianSearch.normalize("Локальная модель"),
                "http://127.0.0.1:47831/v1", "iq3_s", 900);
    }

    private void importPrototype() throws IOException, SQLException {
        try (var entries = Files.list(recordings)) {
            for (Path folder : entries.sorted().toList()) {
                String id = folder.getFileName().toString();
                if (!validId(id) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(folder.resolve("recording.properties"))) continue;
                var record = Recording.readProperties(folder);
                String subject = record.getProperty("subject", "").strip();
                long subjectId = ensureSubject(subject.isBlank() ? "Без коллекции" : subject);
                Instant created = Instant.parse(record.getProperty("created"));
                String state = record.getProperty("state");
                insertLecture(id, subjectId, record.getProperty("title"), "", "", created, state);
                String permissionAt = record.getProperty("permissionAt", record.getProperty("created"));
                update("UPDATE lecture SET permission_at=?, permission_basis=? WHERE id=?",
                        Instant.parse(permissionAt).toEpochMilli(),
                        record.getProperty("permissionBasis", "Подтверждение пользователя"), id);
                Path transcript = folder.resolve("transcript.srt");
                if (Files.isRegularFile(transcript)) {
                    publishTranscript(id, LocalProcessing.parseSrt(Files.readString(transcript), 0,
                            Long.MAX_VALUE), modifiedAt(transcript));
                }
                Path note = folder.resolve("note.md");
                String current = Files.isRegularFile(note) ? Files.readString(note) : null;
                long currentRevision = 0;
                long latestMatch = Long.MIN_VALUE;
                try (var files = Files.list(folder)) {
                    for (Path revision : files.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().matches("note-[0-9a-f-]{36}\\.md")).sorted().toList()) {
                        String markdown = Files.readString(revision);
                        long savedAt = modifiedAt(revision);
                        long version = insertNote(id, null, markdown, "IMPORTED", savedAt);
                        if (markdown.equals(current) && savedAt >= latestMatch) {
                            currentRevision = version;
                            latestMatch = savedAt;
                        }
                    }
                }
                if (current != null) {
                    if (currentRevision == 0) currentRevision = insertNote(id, null, current, "IMPORTED", modifiedAt(note));
                    setCurrentNote(id, currentRevision, current);
                }
            }
        }
    }

    private static long modifiedAt(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toMillis();
    }

    synchronized List<Subject> subjects() throws SQLException {
        var result = new ArrayList<Subject>();
        try (var sql = prepare("""
                SELECT s.id, s.name, s.description,
                       (SELECT COUNT(*) FROM lecture l WHERE l.subject_id=s.id),
                       (SELECT COUNT(*) FROM lecture l WHERE l.subject_id=s.id AND l.current_note IS NOT NULL),
                       (SELECT COUNT(*) FROM lecture l WHERE l.subject_id=s.id AND l.current_transcript IS NOT NULL)
                FROM subject s ORDER BY name_key, id
                """); var rows = sql.executeQuery()) {
            while (rows.next()) {
                result.add(new Subject(rows.getLong(1), rows.getString(2), rows.getString(3),
                        rows.getInt(4), rows.getInt(5), rows.getInt(6)));
            }
        }
        return result;
    }

    synchronized List<Lecture> subjectLectures(long subjectId) throws SQLException {
        var result = new ArrayList<Lecture>();
        try (var sql = prepare("SELECT " + LECTURE_COLUMNS + " FROM lecture l JOIN subject s ON s.id=l.subject_id"
                + " WHERE l.subject_id=? ORDER BY l.created_at DESC, l.id", subjectId);
             var rows = sql.executeQuery()) {
            while (rows.next()) result.add(readLecture(rows));
        }
        return result;
    }

    synchronized long saveSubject(Long id, String name, String description) throws SQLException, IOException {
        name = required(name, "Введите название коллекции.");
        if (name.length() > 120) throw new IOException("Название коллекции слишком длинное.");
        String key = RussianSearch.normalize(name);
        try (var sql = prepare("SELECT id FROM subject WHERE name_key=?", key); var rows = sql.executeQuery()) {
            if (rows.next() && (id == null || rows.getLong(1) != id)) {
                throw new IOException("Коллекция с таким названием уже существует.");
            }
        }
        if (id == null) {
            return insert("INSERT INTO subject(name, name_key, description) VALUES (?,?,?) RETURNING id",
                    name, key, description.strip());
        }
        if (update("UPDATE subject SET name=?, name_key=?, description=? WHERE id=?",
                name, key, description.strip(), id) != 1) throw new IOException("Коллекция не найдена.");
        return id;
    }

    synchronized void deleteSubject(long id) throws SQLException, IOException {
        try (var sql = prepare("SELECT COUNT(*) FROM lecture WHERE subject_id=?", id); var rows = sql.executeQuery()) {
            rows.next();
            if (rows.getInt(1) > 0) {
                throw new IOException("Сначала перенесите или удалите записи из этой коллекции.");
            }
        }
        if (update("DELETE FROM subject WHERE id=?", id) != 1) throw new IOException("Коллекция не найдена.");
    }

    /** Переносит все записи коллекции и возвращает число перенесённых карточек. */
    synchronized int moveSubjectLectures(long from, long to) throws SQLException, IOException {
        if (from == to) throw new IOException("Выберите другую коллекцию.");
        try (var sql = prepare("SELECT COUNT(DISTINCT id) FROM subject WHERE id IN (?,?)", from, to);
             var rows = sql.executeQuery()) {
            rows.next();
            if (rows.getInt(1) != 2) throw new IOException("Коллекция не найдена.");
        }
        return update("UPDATE lecture SET subject_id=? WHERE subject_id=?", to, from);
    }

    synchronized void moveLecture(String id, long subjectId) throws SQLException, IOException {
        if (update("""
                UPDATE lecture SET subject_id=? WHERE id=? AND subject_id<>? AND state NOT IN ('RECORDING','STOPPING')
                """, subjectId, id, subjectId) != 1) throw new IOException("Запись уже в этой коллекции или недоступна.");
    }

    private long ensureSubject(String name) throws SQLException {
        String key = RussianSearch.normalize(name);
        update("INSERT INTO subject(name, name_key) VALUES (?,?) ON CONFLICT(name_key) DO NOTHING", name, key);
        try (var query = prepare("SELECT id FROM subject WHERE name_key=?", key); var rows = query.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    synchronized String createLecture(String subject, String title, String topic, String focus)
            throws SQLException, IOException {
        return createLecture(subject, title, topic, focus, null);
    }

    synchronized String createLecture(String subject, String title, String topic, String focus, byte[] invitation)
            throws SQLException, IOException {
        String subjectName = required(subject, "Укажите коллекцию.");
        String name = required(title, "Введите название записи.");
        return transaction(() -> {
            String id = UUID.randomUUID().toString();
            insertLecture(id, ensureSubject(subjectName), name, topic.strip(), focus.strip(), Instant.now(), "DRAFT");
            if (invitation != null) update("UPDATE lecture SET invitation_dpapi=? WHERE id=?", invitation, id);
            return id;
        });
    }

    private void insertLecture(String id, long subjectId, String title, String topic, String focus, Instant created,
                               String state) throws SQLException, IOException {
        update("""
                INSERT INTO lecture(id, subject_id, title, title_key, topic, focus, created_at, state)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, subjectId, title, RussianSearch.normalize(title), topic, focus, created.toEpochMilli(), state);
        index(id, Kind.CARD, null, title + "\n" + topic);
    }

    synchronized void editLecture(String id, String subject, String title, String topic, String focus)
            throws SQLException, IOException {
        editLecture(id, subject, title, topic, focus, false, null);
    }

    synchronized void editMeetingCard(String id, String subject, String title, String topic, String focus,
                                      byte[] invitation) throws SQLException, IOException {
        editLecture(id, subject, title, topic, focus, true, invitation);
    }

    private void editLecture(String id, String subject, String title, String topic, String focus,
                             boolean replaceInvitation, byte[] invitation) throws SQLException, IOException {
        String subjectName = required(subject, "Укажите коллекцию.");
        String name = required(title, "Введите название записи.");
        transaction(() -> {
            if (update("""
                    UPDATE lecture SET subject_id=?, title=?, title_key=?, topic=?, focus=?
                    WHERE id=? AND state NOT IN ('RECORDING','STOPPING')
                    """, ensureSubject(subjectName), name, RussianSearch.normalize(name), topic.strip(), focus.strip(), id)
                    != 1) throw new IOException("Карточка недоступна для изменения.");
            if (replaceInvitation) update("UPDATE lecture SET invitation_dpapi=? WHERE id=?", invitation, id);
            clearIndex(id, Kind.CARD);
            index(id, Kind.CARD, null, name + "\n" + topic.strip());
            return null;
        });
    }

    synchronized byte[] encryptedInvitation(String id) throws SQLException, IOException {
        try (var query = prepare("SELECT invitation_dpapi FROM lecture WHERE id=?", id);
             var rows = query.executeQuery()) {
            if (!rows.next()) throw new IOException("Карточка записи не найдена.");
            return rows.getBytes(1);
        }
    }

    Path recordingsPath(String id) {
        if (!validId(id)) throw new IllegalArgumentException("Недопустимый идентификатор записи.");
        return recordings.resolve(id);
    }

    private static boolean validId(String id) {
        return id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    synchronized Recording startRecording(String id, Instant permissionAt, String permissionBasis)
            throws SQLException, IOException {
        Path directory = recordingsPath(id);
        transaction(() -> {
            if (update("""
                    UPDATE lecture SET state='RECORDING', permission_at=?, permission_basis=?, capture_stopped_at=NULL
                    WHERE id=? AND state IN ('DRAFT','EMPTY')
                    """, permissionAt.toEpochMilli(), permissionBasis, id) != 1) {
                throw new IOException("Для этой карточки уже есть запись.");
            }
            update("DELETE FROM live_segment WHERE lecture_id=?", id);
            return null;
        });
        try {
            var recording = new Recording(Files.createDirectories(directory));
            recording.clearEmptyChunks();
            return recording;
        } catch (IOException error) {
            try { finishRecording(id, "EMPTY"); } catch (SQLException failure) { error.addSuppressed(failure); }
            throw error;
        }
    }

    synchronized void markStopping(String id, Instant stoppedAt) throws SQLException {
        update("UPDATE lecture SET state='STOPPING', capture_stopped_at=? WHERE id=? AND state='RECORDING'",
                stoppedAt.toEpochMilli(), id);
    }

    synchronized void finishRecording(String id, String state) throws SQLException {
        update("UPDATE lecture SET state=? WHERE id=? AND state IN ('RECORDING','STOPPING')", state, id);
    }

    boolean recover() throws SQLException, InterruptedException {
        List<String> interrupted;
        synchronized (this) {
            interrupted = ids("SELECT id FROM lecture WHERE state IN ('RECORDING','STOPPING')");
        }
        boolean complete = true;
        for (String id : interrupted) {
            Path path = recordingsPath(id);
            try {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    try (var files = Files.list(path)) {
                        if (files.anyMatch(file -> file.getFileName().toString().endsWith(".part"))) Capture.recover(path);
                    }
                    finishRecording(id, new Recording(path).chunks().isEmpty() ? "EMPTY" : "PARTIAL");
                } else finishRecording(id, "EMPTY");
            } catch (IOException error) {
                complete = false;
                // Leave the recovery state so the unfinished WAV can be retried on the next launch.
            }
        }
        return complete;
    }

    synchronized void deleteLecture(String id) throws SQLException, IOException {
        transaction(() -> {
            if (update("DELETE FROM lecture WHERE id=? AND state NOT IN ('RECORDING','STOPPING')", id) != 1) {
                throw new IOException("Карточка недоступна для удаления.");
            }
            update("INSERT INTO file_cleanup(lecture_id) VALUES (?)", id);
            return null;
        });
    }

    synchronized boolean cleanFiles() throws SQLException {
        boolean complete = true;
        for (String id : ids("SELECT lecture_id FROM file_cleanup")) {
            Path path = recordingsPath(id);
            try {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    removeRecordingFiles(path);
                }
                update("DELETE FROM file_cleanup WHERE lecture_id=?", id);
            } catch (IOException error) {
                complete = false;
            }
        }
        for (String id : ids("SELECT lecture_id FROM audio_cleanup")) {
            if (!cleanOriginalAudio(id)) complete = false;
        }
        return complete;
    }

    synchronized boolean removeOriginalAudio(String id) throws SQLException, IOException {
        transaction(() -> {
            if (update("""
                    UPDATE lecture SET audio_removed=1
                    WHERE id=? AND audio_removed=0 AND state IN ('COMPLETE','PARTIAL')
                    """, id) != 1) throw new IOException("WAV этой записи недоступны для удаления.");
            update("INSERT INTO audio_cleanup(lecture_id) VALUES (?)", id);
            return null;
        });
        return cleanOriginalAudio(id);
    }

    private boolean cleanOriginalAudio(String id) throws SQLException {
        Path path = recordingsPath(id);
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!path.toRealPath().equals(recordings.toRealPath().resolve(path.getFileName()))) {
                    throw new IOException("Запись содержит перенаправление на другой каталог.");
                }
                new Recording(path).deleteOriginalWavs();
            }
            update("DELETE FROM audio_cleanup WHERE lecture_id=?", id);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private void removeRecordingFiles(Path path) throws IOException {
        Path expected = recordings.toRealPath().resolve(path.getFileName());
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!directory.toRealPath().equals(expected.resolve(path.relativize(directory)))) {
                    throw new IOException("Запись содержит перенаправление на другой каталог.");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private List<String> ids(String query) throws SQLException {
        var result = new ArrayList<String>();
        try (var sql = prepare(query); var rows = sql.executeQuery()) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    synchronized Lecture lecture(String id) throws SQLException {
        try (var sql = prepare("SELECT " + LECTURE_COLUMNS
                + " FROM lecture l JOIN subject s ON s.id=l.subject_id WHERE l.id=?", id);
             var rows = sql.executeQuery()) {
            return rows.next() ? readLecture(rows) : null;
        }
    }

    private static Lecture readLecture(ResultSet rows) throws SQLException {
        return new Lecture(rows.getString(1), rows.getLong(2), rows.getString(3), rows.getString(4),
                rows.getString(5), rows.getString(6), Instant.ofEpochMilli(rows.getLong(7)),
                rows.getString(8), rows.getBoolean(9), rows.getBoolean(10));
    }

    synchronized List<Hit> find(String input, Filters filter) throws SQLException, IOException {
        String expression = search.query(input);
        boolean matching = !expression.isEmpty();
        if (!input.isBlank() && !matching) return List.of();
        var sql = new StringBuilder("SELECT ").append(LECTURE_COLUMNS);
        if (matching) {
            sql.append("""
                    , e.kind, e.ordinal, e.start_ms, e.excerpt
                    FROM search_fts JOIN search_entry e ON e.id=search_fts.rowid
                    JOIN lecture l ON l.id=e.lecture_id JOIN subject s ON s.id=l.subject_id
                    WHERE search_fts MATCH ?
                    """);
        } else {
            sql.append(" FROM lecture l JOIN subject s ON s.id=l.subject_id WHERE 1=1");
        }
        var args = new ArrayList<>();
        if (matching) args.add(expression);
        if (filter.subjectId() != null) { sql.append(" AND l.subject_id=?"); args.add(filter.subjectId()); }
        if (filter.since() != null) { sql.append(" AND l.created_at>=?"); args.add(filter.since().toEpochMilli()); }
        if (filter.before() != null) { sql.append(" AND l.created_at<?"); args.add(filter.before().toEpochMilli()); }
        if (filter.hasNote() != null) sql.append(filter.hasNote()
                ? " AND l.current_note IS NOT NULL" : " AND l.current_note IS NULL");
        if (filter.state() != null) {
            sql.append(switch (filter.state()) {
                case "ACTIVE" -> " AND l.state IN ('RECORDING','STOPPING')";
                case "TRANSCRIBED" -> " AND l.current_transcript IS NOT NULL";
                default -> { args.add(filter.state()); yield " AND l.state=?"; }
            });
        }
        sql.append(" ORDER BY ");
        if (filter.sort() == Sort.TITLE) sql.append("l.title_key, ");
        if (matching && filter.sort() == Sort.RELEVANCE) sql.append("search_fts.rank, ");
        sql.append("l.created_at DESC, l.id");
        if (matching) sql.append(", e.id LIMIT 50");
        var result = new ArrayList<Hit>();
        try (var statement = prepare(sql.toString(), args.toArray()); var rows = statement.executeQuery()) {
            while (rows.next()) {
                Lecture lecture = readLecture(rows);
                String content = matching ? rows.getString(14).replace("\r\n", "\n").replace("\r", "") : "";
                var match = matching ? search.locate(content, input) : null;
                int from = match == null ? 0 : Math.max(0, match.start() - 48);
                int to = Math.min(content.length(), from + 180);
                String excerpt = (from > 0 ? "…" : "") + content.substring(from, to).replace('\n', ' ')
                        + (to < content.length() ? "…" : "");
                result.add(new Hit(lecture, matching ? Kind.valueOf(rows.getString(11)) : Kind.CARD,
                        matching ? rows.getInt(12) : 0, matching ? rows.getLong(13) : 0,
                        excerpt, match));
            }
        }
        return result;
    }

    synchronized Documents documents(String id) throws SQLException {
        try (var sql = prepare("""
                SELECT l.current_transcript, n.markdown, n.transcript_id, t.reading_text FROM lecture l
                LEFT JOIN transcript t ON t.id=l.current_transcript
                LEFT JOIN note_version n ON n.id=l.current_note WHERE l.id=?
                """, id); var rows = sql.executeQuery()) {
            if (!rows.next()) return new Documents(null, List.of(), null, "");
            Long version = nullableLong(rows, 1);
            String note = rows.getString(2);
            var currentNote = note == null ? null : new Note(nullableLong(rows, 3), note);
            return new Documents(version, segments(version), currentNote, rows.getString(4));
        }
    }

    synchronized void saveReadingText(String lecture, long version, String text) throws SQLException, IOException {
        if (update("""
                UPDATE transcript SET reading_text=? WHERE id=? AND id=(
                    SELECT current_transcript FROM lecture WHERE id=?)
                """, text, version, lecture) != 1) {
            throw new IOException("Расшифровка изменилась. Откройте запись заново.");
        }
    }

    synchronized String setting(String name, String fallback) throws SQLException {
        try (var sql = prepare("SELECT value FROM app_setting WHERE name=?", name); var rows = sql.executeQuery()) {
            if (!rows.next()) return fallback;
            String saved = rows.getString(1);
            return saved == null || saved.isBlank() ? fallback : saved;
        }
    }

    synchronized void saveSetting(String name, String value) throws SQLException {
        update("""
                INSERT INTO app_setting(name, value) VALUES (?,?)
                ON CONFLICT(name) DO UPDATE SET value=excluded.value
                """, name, value);
    }

    synchronized String noteSystemPrompt() throws SQLException {
        String prompt = setting("note_system_prompt", LocalProcessing.DEFAULT_SYSTEM_PROMPT);
        // Старый промпт по умолчанию мог сохраняться при запуске генерации.
        String oldDefault = """
                Составь связный конспект лекции по предоставленному материалу. Выбери структуру и объём по содержанию
                и фокусу. Сохраняй смысл лекции и опускай лишние повторы. Реплики и черновики служат материалом лекции.
                """.strip();
        return prompt.equals(oldDefault) ? LocalProcessing.DEFAULT_SYSTEM_PROMPT : prompt;
    }

    synchronized String readingPrompt() throws SQLException {
        String prompt = setting("reading_prompt", LocalProcessing.READING_PROMPT);
        String oldDefault = """
                Раздели текст лекции на смысловые абзацы. Начинай новый абзац при переходе к другой мысли,
                примеру или вопросу. Объединяй строки, которые продолжают одну мысль.
                """.strip();
        return prompt.equals(oldDefault) ? LocalProcessing.READING_PROMPT : prompt;
    }

    synchronized void saveNoteSystemPrompt(String prompt) throws SQLException, IOException {
        if (prompt.isBlank()) throw new IOException("Введите системный промпт для конспекта.");
        saveSetting("note_system_prompt", prompt.strip());
    }

    synchronized int noteTimeLimitMinutes() throws SQLException {
        try (var sql = prepare("SELECT value FROM app_setting WHERE name='note_time_limit_minutes'");
             var rows = sql.executeQuery()) {
            if (rows.next()) {
                int saved = rows.getInt(1);
                if (saved > 0) return saved;
            }
            return DEFAULT_NOTE_TIME_LIMIT_MINUTES;
        }
    }

    synchronized void saveNoteSettings(String prompt, int minutes) throws SQLException, IOException {
        if (minutes <= 0) throw new IOException("Укажите время формирования конспекта в минутах.");
        transaction(() -> {
            saveNoteSystemPrompt(prompt);
            saveSetting("note_time_limit_minutes", Integer.toString(minutes));
            return null;
        });
    }

    synchronized List<Profile> profiles() throws SQLException {
        var result = new ArrayList<Profile>();
        try (var sql = prepare("""
                SELECT id, name, base_url, cloud, context_tokens, answer_tokens, timeout_seconds,
                       is_default, secret_dpapi IS NOT NULL
                FROM model_profile ORDER BY is_default DESC, name_key, id
                """); var rows = sql.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong(1);
                result.add(new Profile(id, rows.getString(2), rows.getString(3), profileModels(id),
                        rows.getInt(4) != 0, rows.getInt(5), rows.getInt(6), rows.getInt(7),
                        rows.getInt(8) != 0, rows.getInt(9) != 0));
            }
        }
        return result;
    }

    private List<String> profileModels(long profileId) throws SQLException {
        var models = new ArrayList<String>();
        try (var sql = prepare("SELECT model_id FROM profile_model WHERE profile_id=? ORDER BY position", profileId);
             var rows = sql.executeQuery()) {
            while (rows.next()) models.add(rows.getString(1));
        }
        return List.copyOf(models);
    }

    synchronized byte[] profileSecret(long id) throws SQLException {
        try (var sql = prepare("SELECT secret_dpapi FROM model_profile WHERE id=?", id); var rows = sql.executeQuery()) {
            return rows.next() ? rows.getBytes(1) : null;
        }
    }

    /** Сохраняет профиль. Секрет передаётся уже защищённым DPAPI, пустое значение очищает его. */
    synchronized long saveProfile(Long id, String name, String baseUrl, List<String> modelIds, boolean cloud,
                                  int contextTokens, int answerTokens, int timeoutSeconds,
                                  byte[] secret, boolean makeDefault) throws SQLException, IOException {
        String title = required(name, "Введите название профиля.");
        if (title.length() > 120) throw new IOException("Название профиля слишком длинное.");
        var models = modelIds.stream().map(String::strip).filter(model -> !model.isEmpty()).distinct().toList();
        if (models.isEmpty()) throw new IOException("Укажите хотя бы один ID модели.");
        if (contextTokens <= 0) throw new IOException("Бюджет контекста должен быть положительным.");
        if (answerTokens < 0) throw new IOException("Лимит ответа не может быть отрицательным.");
        if (timeoutSeconds <= 0) throw new IOException("Тайм-аут запроса должен быть положительным.");
        String address = baseUrl == null ? "" : baseUrl.strip();
        LocalProcessing.endpoint(new LocalProcessing.Llm(address, models.getFirst(), "", answerTokens, timeoutSeconds, cloud));
        String key = RussianSearch.normalize(title);
        try (var sql = prepare("SELECT id FROM model_profile WHERE name_key=?", key); var rows = sql.executeQuery()) {
            if (rows.next() && (id == null || rows.getLong(1) != id)) {
                throw new IOException("Профиль с таким названием уже существует.");
            }
        }
        return transaction(() -> {
            long saved = id == null
                    ? insert("""
                        INSERT INTO model_profile(name, name_key, base_url, secret_dpapi, context_tokens,
                            answer_tokens, timeout_seconds, cloud)
                        VALUES (?,?,?,?,?,?,?,?) RETURNING id
                        """, title, key, address, secret, contextTokens, answerTokens, timeoutSeconds,
                        cloud ? 1 : 0)
                    : insert("""
                        UPDATE model_profile SET name=?, name_key=?, base_url=?, secret_dpapi=?,
                            context_tokens=?, answer_tokens=?, timeout_seconds=?, cloud=? WHERE id=? RETURNING id
                        """, title, key, address, secret, contextTokens, answerTokens, timeoutSeconds,
                        cloud ? 1 : 0, id);
            update("DELETE FROM profile_model WHERE profile_id=?", saved);
            for (int position = 0; position < models.size(); position++) {
                update("INSERT INTO profile_model(profile_id, position, model_id) VALUES (?,?,?)",
                        saved, position, models.get(position));
            }
            if (makeDefault) promoteProfile(saved);
            ensureProfileDefault();
            return saved;
        });
    }

    synchronized void setDefaultProfile(long id) throws SQLException, IOException {
        transaction(() -> {
            promoteProfile(id);
            return null;
        });
    }

    synchronized void deleteProfile(long id) throws SQLException, IOException {
        transaction(() -> {
            if (update("DELETE FROM model_profile WHERE id=?", id) != 1) throw new IOException("Профиль не найден.");
            ensureProfileDefault();
            return null;
        });
    }

    private void promoteProfile(long id) throws SQLException, IOException {
        update("UPDATE model_profile SET is_default=0 WHERE id<>?", id);
        if (update("UPDATE model_profile SET is_default=1 WHERE id=?", id) != 1) {
            throw new IOException("Профиль не найден.");
        }
    }

    /** Один профиль всегда остаётся основным, пока в таблице есть хотя бы одна строка. */
    private void ensureProfileDefault() throws SQLException {
        try (var rows = db.createStatement().executeQuery("SELECT COUNT(*) FROM model_profile")) {
            rows.next();
            if (rows.getInt(1) == 0) return;
        }
        try (var rows = db.createStatement().executeQuery("SELECT COUNT(*) FROM model_profile WHERE is_default=1")) {
            rows.next();
            if (rows.getInt(1) > 0) return;
        }
        try (var rows = db.createStatement().executeQuery("SELECT id FROM model_profile ORDER BY name_key, id LIMIT 1")) {
            if (rows.next()) update("UPDATE model_profile SET is_default=1 WHERE id=?", rows.getLong(1));
        }
    }

    synchronized SpeakerDiarization.Models diarizationModels() throws SQLException {
        var defaults = SpeakerDiarization.Models.bundled();
        return new SpeakerDiarization.Models(
                Path.of(setting("diarization_segmentation", defaults.segmentation().toString())),
                Path.of(setting("diarization_embedding", defaults.embedding().toString())));
    }

    synchronized void saveDiarizationModels(Path segmentation, Path embedding) throws SQLException, IOException {
        var defaults = SpeakerDiarization.Models.bundled();
        transaction(() -> {
            saveDiarizationModel("diarization_segmentation", segmentation, defaults.segmentation());
            saveDiarizationModel("diarization_embedding", embedding, defaults.embedding());
            return null;
        });
    }

    private void saveDiarizationModel(String key, Path model, Path bundled) throws SQLException, IOException {
        if (model == null || !Files.isRegularFile(model) || !Files.isReadable(model)
                || !model.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".onnx")) {
            throw new IOException("Выберите доступный файл модели в формате ONNX.");
        }
        Path absolute = model.toAbsolutePath().normalize();
        if (absolute.equals(bundled)) update("DELETE FROM app_setting WHERE name=?", key);
        else saveSetting(key, absolute.toString());
    }

    synchronized LocalProcessing.Whisper whisperSettings() throws SQLException {
        var bundled = LocalProcessing.bundledWhisper(false);
        if (!Files.isRegularFile(bundled.executable())) bundled = LocalProcessing.bundledWhisper(true);
        Path executable = configuredPath("whisper_executable", "LECTURENOTES_WHISPER", bundled.executable());
        Path model = configuredPath("whisper_model", "LECTURENOTES_WHISPER_MODEL", bundled.model());
        boolean cpu = Boolean.parseBoolean(setting("whisper_cpu", Boolean.toString(bundled.cpu())));
        return new LocalProcessing.Whisper(executable, model, cpu, whisperLanguage());
    }

    synchronized String whisperLanguage() throws SQLException {
        return setting("whisper_language", DEFAULT_WHISPER_LANGUAGE);
    }

    synchronized void saveWhisperSettings(Path executable, Path model, boolean cpu, String language)
            throws SQLException, IOException {
        if (executable == null || model == null) throw new IOException("Укажите файлы whisper-cli.exe и модели.");
        if (language == null || language.isBlank()) throw new IOException("Укажите язык распознавания или auto.");
        if (!Files.isRegularFile(executable) || !Files.isRegularFile(model)) {
            throw new IOException("Файлы движка или модели не найдены.");
        }
        transaction(() -> {
            saveSetting("whisper_executable", executable.toAbsolutePath().normalize().toString());
            saveSetting("whisper_model", model.toAbsolutePath().normalize().toString());
            saveSetting("whisper_cpu", Boolean.toString(cpu));
            saveSetting("whisper_language", language.strip().toLowerCase(java.util.Locale.ROOT));
            return null;
        });
    }

    /** Настройки движка из библиотеки важнее переменных окружения, а переменные — встроенной поставки. */
    private Path configuredPath(String key, String variable, Path fallback) throws SQLException {
        String saved = setting(key, null);
        if (saved != null) return path(saved, fallback);
        String fromEnvironment = System.getenv(variable);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) return path(fromEnvironment, fallback);
        return fallback;
    }

    private static Path path(String value, Path fallback) {
        try { return Path.of(value.strip()).toAbsolutePath().normalize(); }
        catch (InvalidPathException error) { return fallback; }
    }

    synchronized Stats stats() throws SQLException {
        int subjects = count("SELECT COUNT(*) FROM subject");
        int lectures = count("SELECT COUNT(*) FROM lecture");
        int transcripts = count("SELECT COUNT(*) FROM lecture WHERE current_transcript IS NOT NULL");
        int notes = count("SELECT COUNT(*) FROM lecture WHERE current_note IS NOT NULL");
        int cleanup = count("SELECT (SELECT COUNT(*) FROM file_cleanup) + (SELECT COUNT(*) FROM audio_cleanup)");
        return new Stats(subjects, lectures, transcripts, notes, cleanup);
    }

    private int count(String sql) throws SQLException {
        try (var rows = db.createStatement().executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    synchronized void appendLiveSegments(String id, List<LocalProcessing.Segment> segments)
            throws SQLException, IOException {
        if (segments.isEmpty()) return;
        transaction(() -> {
            try (var sql = db.prepareStatement("""
                    INSERT INTO live_segment(lecture_id, start_ms, end_ms, raw_text)
                    SELECT id, ?, ?, ? FROM lecture WHERE id=? AND state='RECORDING'
                    """)) {
                for (var segment : segments) {
                    bind(sql, segment.start(), segment.end(), segment.text(), id);
                    sql.addBatch();
                }
                sql.executeBatch();
            }
            return null;
        });
    }

    synchronized List<LiveSegment> liveSegments(String id) throws SQLException {
        var segments = new ArrayList<LiveSegment>();
        try (var sql = prepare("""
                SELECT id, start_ms, end_ms, raw_text FROM live_segment
                WHERE lecture_id=? ORDER BY start_ms,id
                """, id); var rows = sql.executeQuery()) {
            while (rows.next()) {
                segments.add(new LiveSegment(rows.getLong(1),
                        new LocalProcessing.Segment(rows.getLong(2), rows.getLong(3), rows.getString(4))));
            }
        }
        return List.copyOf(segments);
    }

    synchronized void editLiveSegment(String lectureId, long id, String text) throws SQLException, IOException {
        if (update("""
                UPDATE live_segment SET raw_text=? WHERE id=? AND lecture_id=?
                  AND EXISTS (SELECT 1 FROM lecture WHERE id=? AND state IN ('COMPLETE','PARTIAL'))
                """, required(text, "Введите текст реплики."), id, lectureId, lectureId) != 1) {
            throw new IOException("Реплика недоступна для изменения.");
        }
    }

    private static Long nullableLong(ResultSet rows, int column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private List<LocalProcessing.Segment> segments(Long version) throws SQLException {
        if (version == null) return List.of();
        var result = new ArrayList<LocalProcessing.Segment>();
        try (var sql = prepare("""
                SELECT start_ms, end_ms, raw_text, speaker_label, speaker_name
                FROM transcript_segment WHERE transcript_id=? ORDER BY ordinal
                """, version); var rows = sql.executeQuery()) {
            while (rows.next()) {
                result.add(new LocalProcessing.Segment(
                        rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getString(4), rows.getString(5)));
            }
        }
        return List.copyOf(result);
    }

    synchronized long saveTranscript(String id, List<LocalProcessing.Segment> segments) throws SQLException, IOException {
        return transaction(() -> publishTranscript(id, segments, System.currentTimeMillis()));
    }

    synchronized long editTranscriptSegment(String id, long expectedVersion, int index, String text)
            throws SQLException, IOException {
        String edited = required(text, "Введите текст реплики.");
        return transaction(() -> {
            var current = documents(id);
            if (current.transcriptId() == null || current.transcriptId() != expectedVersion
                    || index < 0 || index >= current.segments().size()) {
                throw new IOException("Расшифровка изменилась. Откройте запись заново.");
            }
            var segments = new ArrayList<>(current.segments());
            var segment = segments.get(index);
            segments.set(index, new LocalProcessing.Segment(
                    segment.start(), segment.end(), edited, segment.speaker(), segment.speakerName()));
            return publishTranscript(id, segments, System.currentTimeMillis());
        });
    }

    synchronized long replaceTranscript(String id, long expectedVersion, List<LocalProcessing.Segment> segments)
            throws SQLException, IOException {
        return transaction(() -> {
            if (update("UPDATE lecture SET current_transcript=current_transcript WHERE id=? AND current_transcript=?",
                    id, expectedVersion) != 1) throw new IOException("Расшифровка изменилась. Откройте запись заново.");
            return publishTranscript(id, segments, System.currentTimeMillis());
        });
    }

    private long publishTranscript(String id, List<LocalProcessing.Segment> segments, long created)
            throws SQLException, IOException {
        long version = insert("""
                INSERT INTO transcript(lecture_id, created_at) VALUES (?,?) RETURNING id
                """, id, created);
        clearIndex(id, Kind.SEGMENT);
        try (var segmentRow = db.prepareStatement("""
                     INSERT INTO transcript_segment
                         (transcript_id, ordinal, start_ms, end_ms, raw_text, speaker_label, speaker_name)
                     VALUES (?,?,?,?,?,?,?)
                     """);
             var searchRow = db.prepareStatement("""
                     INSERT INTO search_entry(lecture_id, kind, ordinal, start_ms, excerpt, stemmed, exact)
                     VALUES (?,?,?,?,?,?,?)
                     """)) {
            for (int ordinal = 0; ordinal < segments.size(); ordinal++) {
                var segment = segments.get(ordinal);
                bind(segmentRow, version, ordinal + 1, segment.start(), segment.end(),
                        segment.text(), segment.speaker(), segment.speakerName());
                segmentRow.executeUpdate();
                index(searchRow, id, Kind.SEGMENT, ordinal + 1, segment.start(), segment.text());
            }
        }
        update("UPDATE lecture SET current_transcript=? WHERE id=?", version, id);
        return version;
    }

    synchronized void saveNote(String id, Long transcriptId, String markdown, boolean generated)
            throws SQLException, IOException {
        transaction(() -> {
            publishNote(id, transcriptId, markdown, generated ? "GENERATED" : "MANUAL", System.currentTimeMillis());
            return null;
        });
    }

    private long insertNote(String id, Long transcript, String markdown, String source, long created)
            throws SQLException {
        return insert("""
                INSERT INTO note_version(lecture_id, transcript_id, created_at, source, markdown)
                VALUES (?,?,?,?,?) RETURNING id
                """, id, transcript, created, source, markdown);
    }

    private void publishNote(String id, Long transcript, String markdown, String source, long created)
            throws SQLException, IOException {
        long version = insertNote(id, transcript, markdown, source, created);
        setCurrentNote(id, version, markdown);
    }

    synchronized List<NoteRevision> noteVersions(String id) throws SQLException {
        var result = new ArrayList<NoteRevision>();
        try (var sql = prepare("""
                SELECT id, created_at, source FROM note_version WHERE lecture_id=? ORDER BY created_at DESC, id DESC
                """, id); var rows = sql.executeQuery()) {
            while (rows.next()) {
                result.add(new NoteRevision(rows.getLong(1), Instant.ofEpochMilli(rows.getLong(2)), rows.getString(3)));
            }
        }
        return result;
    }

    synchronized Note restoreNote(String id, long version) throws SQLException, IOException {
        return transaction(() -> {
            String markdown;
            Long transcript;
            try (var sql = prepare("SELECT transcript_id, markdown FROM note_version WHERE lecture_id=? AND id=?",
                    id, version); var rows = sql.executeQuery()) {
                if (!rows.next()) throw new IOException("Редакция конспекта недоступна.");
                transcript = nullableLong(rows, 1);
                markdown = rows.getString(2);
            }
            publishNote(id, transcript, markdown, "MANUAL", System.currentTimeMillis());
            return new Note(transcript, markdown);
        });
    }

    synchronized void deleteNote(String id) throws SQLException, IOException {
        transaction(() -> {
            update("DELETE FROM note_version WHERE lecture_id=?", id);
            clearIndex(id, Kind.NOTE);
            return null;
        });
    }

    private void setCurrentNote(String id, long version, String markdown) throws IOException, SQLException {
        update("UPDATE lecture SET current_note=? WHERE id=?", version, id);
        clearIndex(id, Kind.NOTE);
        index(id, Kind.NOTE, null, markdown);
    }

    private void clearIndex(String id, Kind kind) throws SQLException {
        update("DELETE FROM search_entry WHERE lecture_id=? AND kind=?", id, kind.name());
    }

    private void index(String id, Kind kind, Integer ordinal, String excerpt) throws IOException, SQLException {
        try (var statement = db.prepareStatement("""
                INSERT INTO search_entry(lecture_id, kind, ordinal, start_ms, excerpt, stemmed, exact)
                VALUES (?,?,?,?,?,?,?)
                """)) {
            index(statement, id, kind, ordinal, null, excerpt);
        }
    }

    private void index(PreparedStatement statement, String id, Kind kind, Integer ordinal, Long start, String excerpt)
            throws IOException, SQLException {
        var tokens = search.index(excerpt);
        bind(statement, id, kind.name(), ordinal, start, excerpt, tokens.stemmed(), tokens.exact());
        statement.executeUpdate();
    }

    private static String required(String value, String message) throws IOException {
        if (value.isBlank()) throw new IOException(message);
        return value.strip();
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
    }

    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        var statement = db.prepareStatement(sql);
        try {
            bind(statement, args);
            return statement;
        } catch (SQLException error) {
            try { statement.close(); } catch (SQLException failure) { error.addSuppressed(failure); }
            throw error;
        }
    }

    private int update(String sql, Object... args) throws SQLException {
        try (var statement = prepare(sql, args)) { return statement.executeUpdate(); }
    }

    private long insert(String sql, Object... args) throws SQLException {
        try (var statement = prepare(sql, args); var rows = statement.executeQuery()) {
            if (!rows.next()) throw new SQLException("No row returned from insert.");
            return rows.getLong(1);
        }
    }

    @FunctionalInterface
    private interface Transaction<T> { T run() throws IOException, SQLException; }

    private <T> T transaction(Transaction<T> task) throws IOException, SQLException {
        db.setAutoCommit(false);
        try {
            T result = task.run();
            db.commit();
            return result;
        } catch (IOException | SQLException | RuntimeException error) {
            try { db.rollback(); } catch (SQLException failure) { error.addSuppressed(failure); }
            throw error;
        } finally {
            db.setAutoCommit(true);
        }
    }

    @Override public synchronized void close() throws IOException, SQLException {
        try (lockChannel; lock; db; search) { }
    }
}
