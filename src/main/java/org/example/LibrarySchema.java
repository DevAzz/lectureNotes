package org.example;

import java.util.List;

final class LibrarySchema {
    static final String V2 = "ALTER TABLE lecture ADD COLUMN invitation_dpapi BLOB";
    static final List<String> V3 = List.of(
            """
            CREATE TABLE live_segment (
                id INTEGER PRIMARY KEY,
                lecture_id TEXT NOT NULL REFERENCES lecture(id) ON DELETE CASCADE,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                raw_text TEXT NOT NULL
            )
            """,
            "CREATE INDEX live_segment_lecture ON live_segment(lecture_id, start_ms, id)",
            "CREATE TABLE app_setting (name TEXT PRIMARY KEY, value TEXT NOT NULL)"
    );
    static final List<String> V4 = List.of(
            "ALTER TABLE lecture ADD COLUMN audio_removed INTEGER NOT NULL DEFAULT 0",
            "CREATE TABLE audio_cleanup (lecture_id TEXT PRIMARY KEY REFERENCES lecture(id) ON DELETE CASCADE)"
    );
    static final List<String> V5 = List.of(
            "ALTER TABLE transcript_segment ADD COLUMN speaker_label TEXT",
            "ALTER TABLE transcript_segment ADD COLUMN speaker_name TEXT"
    );
    static final List<String> V6 = List.of(
            """
            CREATE TABLE model_profile (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                name_key TEXT NOT NULL UNIQUE,
                base_url TEXT NOT NULL,
                model_id TEXT NOT NULL,
                secret_dpapi BLOB,
                context_tokens INTEGER NOT NULL DEFAULT 8192,
                answer_tokens INTEGER NOT NULL DEFAULT 0,
                timeout_seconds INTEGER NOT NULL DEFAULT 900,
                cloud INTEGER NOT NULL DEFAULT 0,
                is_default INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE UNIQUE INDEX model_profile_default ON model_profile(is_default) WHERE is_default=1"
    );

    static final List<String> V1 = List.of(
            """
            CREATE TABLE subject (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                name_key TEXT NOT NULL UNIQUE,
                description TEXT NOT NULL DEFAULT ''
            )
            """,
            """
            CREATE TABLE lecture (
                id TEXT PRIMARY KEY,
                subject_id INTEGER NOT NULL REFERENCES subject(id) ON DELETE RESTRICT,
                title TEXT NOT NULL,
                title_key TEXT NOT NULL,
                topic TEXT NOT NULL DEFAULT '',
                focus TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                state TEXT NOT NULL CHECK (state IN ('DRAFT','RECORDING','STOPPING','EMPTY','COMPLETE','PARTIAL')),
                permission_at INTEGER,
                permission_basis TEXT,
                capture_stopped_at INTEGER,
                current_transcript INTEGER REFERENCES transcript(id) ON DELETE SET NULL,
                current_note INTEGER REFERENCES note_version(id) ON DELETE SET NULL
            )
            """,
            "CREATE INDEX lecture_subject ON lecture(subject_id)",
            """
            CREATE TABLE transcript (
                id INTEGER PRIMARY KEY,
                lecture_id TEXT NOT NULL REFERENCES lecture(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL
            )
            """,
            "CREATE INDEX transcript_lecture ON transcript(lecture_id)",
            """
            CREATE TABLE transcript_segment (
                transcript_id INTEGER NOT NULL REFERENCES transcript(id) ON DELETE CASCADE,
                ordinal INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                raw_text TEXT NOT NULL,
                PRIMARY KEY (transcript_id, ordinal)
            )
            """,
            """
            CREATE TABLE note_version (
                id INTEGER PRIMARY KEY,
                lecture_id TEXT NOT NULL REFERENCES lecture(id) ON DELETE CASCADE,
                transcript_id INTEGER REFERENCES transcript(id) ON DELETE SET NULL,
                created_at INTEGER NOT NULL,
                source TEXT NOT NULL CHECK (source IN ('MANUAL','GENERATED','IMPORTED')),
                markdown TEXT NOT NULL
            )
            """,
            "CREATE INDEX note_lecture ON note_version(lecture_id)",
            """
            CREATE TABLE search_entry (
                id INTEGER PRIMARY KEY,
                lecture_id TEXT NOT NULL REFERENCES lecture(id) ON DELETE CASCADE,
                kind TEXT NOT NULL CHECK (kind IN ('CARD','SEGMENT','NOTE')),
                ordinal INTEGER,
                start_ms INTEGER,
                excerpt TEXT NOT NULL,
                stemmed TEXT NOT NULL,
                exact TEXT NOT NULL
            )
            """,
            "CREATE INDEX search_entry_lecture_kind ON search_entry(lecture_id, kind)",
            """
            CREATE VIRTUAL TABLE search_fts USING fts5(
                stemmed, exact, content='search_entry', content_rowid='id',
                tokenize='unicode61 remove_diacritics 0'
            )
            """,
            """
            CREATE TRIGGER search_entry_insert AFTER INSERT ON search_entry BEGIN
                INSERT INTO search_fts(rowid, stemmed, exact) VALUES (new.id, new.stemmed, new.exact);
            END
            """,
            """
            CREATE TRIGGER search_entry_delete AFTER DELETE ON search_entry BEGIN
                INSERT INTO search_fts(search_fts, rowid, stemmed, exact)
                VALUES ('delete', old.id, old.stemmed, old.exact);
            END
            """,
            "CREATE TABLE file_cleanup (lecture_id TEXT PRIMARY KEY)"
    );
}
