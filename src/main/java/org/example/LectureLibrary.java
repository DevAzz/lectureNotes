package org.example;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
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
    record Subject(long id, String name, String description) {
        @Override public String toString() { return name; }
    }

    record Lecture(String id, long subjectId, String subject, String title, String topic,
                   String focus, Instant created, String state, boolean hasNote) {
        boolean capturing() { return state.equals("RECORDING") || state.equals("STOPPING"); }
        boolean canRecord() { return state.equals("DRAFT") || state.equals("EMPTY"); }
        boolean hasAudio() { return state.equals("COMPLETE") || state.equals("PARTIAL"); }
        String label() { return subject + " · " + title; }
    }

    enum Kind { CARD, SEGMENT, NOTE }
    enum Sort { DATE, TITLE, RELEVANCE }
    record Filters(Long subjectId, Instant since, Instant before, Boolean hasNote, String state, Sort sort) { }
    record Hit(Lecture lecture, Kind kind, int ordinal, long startMillis, String excerpt) { }
    record Documents(Long transcriptId, List<LocalProcessing.Segment> segments, Note note) { }
    record Note(Long transcriptId, String markdown) { }
    record NoteRevision(long id, Instant created, String source) { }

    private static final String LECTURE_COLUMNS = """
            l.id, l.subject_id, s.name, l.title, l.topic, l.focus, l.created_at, l.state, l.current_note IS NOT NULL
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
        if (version == 1) return;
        if (version != 0) throw new IOException("Библиотека создана более новой версией приложения.");
        transaction(() -> {
            try (var statement = db.createStatement()) {
                for (String sql : LibrarySchema.V1) statement.execute(sql);
                importPrototype();
                statement.execute("PRAGMA user_version = 1");
            }
            return null;
        });
    }

    private void importPrototype() throws IOException, SQLException {
        try (var entries = Files.list(recordings)) {
            for (Path folder : entries.sorted().toList()) {
                String id = folder.getFileName().toString();
                if (!validId(id) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(folder.resolve("recording.properties"))) continue;
                var record = Recording.readProperties(folder);
                String subject = record.getProperty("subject", "").strip();
                long subjectId = ensureSubject(subject.isBlank() ? "Без предмета" : subject);
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
        try (var sql = prepare("SELECT id, name, description FROM subject ORDER BY name_key, id");
             var rows = sql.executeQuery()) {
            while (rows.next()) result.add(new Subject(rows.getLong(1), rows.getString(2), rows.getString(3)));
        }
        return result;
    }

    synchronized void saveSubject(Long id, String name, String description) throws SQLException, IOException {
        name = required(name, "Введите название предмета.");
        String key = RussianSearch.normalize(name);
        try (var sql = prepare("SELECT id FROM subject WHERE name_key=?", key); var rows = sql.executeQuery()) {
            if (rows.next() && (id == null || rows.getLong(1) != id)) {
                throw new IOException("Предмет с таким названием уже существует.");
            }
        }
        if (id == null) {
            update("INSERT INTO subject(name, name_key, description) VALUES (?,?,?)", name, key, description.strip());
        } else {
            update("UPDATE subject SET name=?, name_key=?, description=? WHERE id=?", name, key, description.strip(), id);
        }
    }

    synchronized void deleteSubject(long id) throws SQLException, IOException {
        try (var sql = prepare("SELECT id FROM lecture WHERE subject_id=? LIMIT 1", id); var rows = sql.executeQuery()) {
            if (rows.next()) throw new IOException("Сначала перенесите или удалите лекции этого предмета.");
        }
        update("DELETE FROM subject WHERE id=?", id);
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
        String subjectName = required(subject, "Введите предмет.");
        String name = required(title, "Введите название лекции.");
        return transaction(() -> {
            String id = UUID.randomUUID().toString();
            insertLecture(id, ensureSubject(subjectName), name, topic.strip(), focus.strip(), Instant.now(), "DRAFT");
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
        String subjectName = required(subject, "Введите предмет.");
        String name = required(title, "Введите название лекции.");
        transaction(() -> {
            if (update("""
                    UPDATE lecture SET subject_id=?, title=?, title_key=?, topic=?, focus=?
                    WHERE id=? AND state NOT IN ('RECORDING','STOPPING')
                    """, ensureSubject(subjectName), name, RussianSearch.normalize(name), topic.strip(), focus.strip(), id)
                    != 1) throw new IOException("Карточка недоступна для изменения.");
            clearIndex(id, Kind.CARD);
            index(id, Kind.CARD, null, name + "\n" + topic.strip());
            return null;
        });
    }

    Path recordingsPath(String id) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid lecture ID.");
        return recordings.resolve(id);
    }

    private static boolean validId(String id) {
        return id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    synchronized Recording startRecording(String id) throws SQLException, IOException {
        Path directory = recordingsPath(id);
        if (update("""
                UPDATE lecture SET state='RECORDING', permission_at=?, permission_basis=?, capture_stopped_at=NULL
                WHERE id=? AND state IN ('DRAFT','EMPTY')
                """, System.currentTimeMillis(), "Подтверждение пользователя", id) != 1) {
            throw new IOException("Для этой карточки уже есть запись.");
        }
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
        return complete;
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
                rows.getString(8), rows.getBoolean(9));
    }

    synchronized List<Hit> find(String input, Filters filter) throws SQLException, IOException {
        String expression = search.query(input);
        boolean matching = !expression.isEmpty();
        if (!input.isBlank() && !matching) return List.of();
        var sql = new StringBuilder("SELECT ").append(LECTURE_COLUMNS);
        if (matching) {
            sql.append("""
                    , e.kind, e.ordinal, e.start_ms, substr(replace(e.excerpt, char(10), ' '), 1, 180)
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
                result.add(new Hit(lecture, matching ? Kind.valueOf(rows.getString(10)) : Kind.CARD,
                        matching ? rows.getInt(11) : 0, matching ? rows.getLong(12) : 0,
                        matching ? rows.getString(13) : ""));
            }
        }
        return result;
    }

    synchronized Documents documents(String id) throws SQLException {
        try (var sql = prepare("""
                SELECT l.current_transcript, n.markdown, n.transcript_id FROM lecture l
                LEFT JOIN note_version n ON n.id=l.current_note WHERE l.id=?
                """, id); var rows = sql.executeQuery()) {
            if (!rows.next()) return new Documents(null, List.of(), null);
            Long version = nullableLong(rows, 1);
            String note = rows.getString(2);
            var currentNote = note == null ? null : new Note(nullableLong(rows, 3), note);
            return new Documents(version, segments(version), currentNote);
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
                SELECT start_ms, end_ms, raw_text FROM transcript_segment WHERE transcript_id=? ORDER BY ordinal
                """, version); var rows = sql.executeQuery()) {
            while (rows.next()) {
                result.add(new LocalProcessing.Segment(rows.getLong(1), rows.getLong(2), rows.getString(3)));
            }
        }
        return List.copyOf(result);
    }

    synchronized long saveTranscript(String id, List<LocalProcessing.Segment> segments) throws SQLException, IOException {
        return transaction(() -> publishTranscript(id, segments, System.currentTimeMillis()));
    }

    private long publishTranscript(String id, List<LocalProcessing.Segment> segments, long created)
            throws SQLException, IOException {
        long version = insert("""
                INSERT INTO transcript(lecture_id, created_at) VALUES (?,?) RETURNING id
                """, id, created);
        clearIndex(id, Kind.SEGMENT);
        try (var segmentRow = db.prepareStatement("""
                     INSERT INTO transcript_segment(transcript_id, ordinal, start_ms, end_ms, raw_text) VALUES (?,?,?,?,?)
                     """);
             var searchRow = db.prepareStatement("""
                     INSERT INTO search_entry(lecture_id, kind, ordinal, start_ms, excerpt, stemmed, exact)
                     VALUES (?,?,?,?,?,?,?)
                     """)) {
            for (int ordinal = 0; ordinal < segments.size(); ordinal++) {
                var segment = segments.get(ordinal);
                bind(segmentRow, version, ordinal + 1, segment.start(), segment.end(), segment.text());
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
