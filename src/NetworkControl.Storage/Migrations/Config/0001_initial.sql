-- config.db 初始結構（設計文檔 §13.2）
-- 版本號使用 PRAGMA user_version 記錄，由 Migrator 維護。

CREATE TABLE apps (
    id            INTEGER PRIMARY KEY,
    path          TEXT    NOT NULL,
    service_name  TEXT    NOT NULL DEFAULT '',
    package_id    TEXT    NOT NULL DEFAULT '',
    file_size     INTEGER,
    file_mtime    INTEGER,
    display_name  TEXT,
    signer_id     TEXT,
    signing_id    TEXT,
    is_platform   INTEGER NOT NULL DEFAULT 0,
    sha256        TEXT,
    first_seen    INTEGER NOT NULL,
    last_seen     INTEGER NOT NULL,
    UNIQUE (path, service_name, package_id)
);

CREATE TABLE profiles (
    id            TEXT    PRIMARY KEY,
    name          TEXT    NOT NULL,
    mode          INTEGER NOT NULL,
    auto_activate TEXT,
    sort_order    INTEGER NOT NULL
);

INSERT INTO profiles (id, name, mode, auto_activate, sort_order)
VALUES ('00000000-0000-0000-0000-000000000001', '默認', 0, NULL, 0);

CREATE TABLE rules (
    id              TEXT    PRIMARY KEY,
    profile_id      TEXT    REFERENCES profiles(id) ON DELETE CASCADE,
    app_matcher     TEXT,
    conditions      TEXT    NOT NULL,
    action          INTEGER NOT NULL,
    lifetime        INTEGER NOT NULL,
    expires_at      INTEGER,
    bound_pid       INTEGER,
    bound_start     INTEGER,
    source          INTEGER NOT NULL,
    enabled         INTEGER NOT NULL DEFAULT 1,
    manual_priority INTEGER NOT NULL DEFAULT 0,
    note            TEXT,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    last_hit_at     INTEGER,
    hit_count       INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX ix_rules_profile ON rules(profile_id);

CREATE TABLE rule_history (
    id         TEXT    PRIMARY KEY,
    snapshot   TEXT    NOT NULL,
    ended_at   INTEGER NOT NULL,
    end_reason INTEGER NOT NULL
);

CREATE INDEX ix_rule_history_ended ON rule_history(ended_at);

CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
