package com.networkcontrol.android.kotlin.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ConfigDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
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
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE profiles (
                id            TEXT    PRIMARY KEY,
                name          TEXT    NOT NULL,
                mode          INTEGER NOT NULL,
                auto_activate TEXT,
                sort_order    INTEGER NOT NULL
            );
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT INTO profiles (id, name, mode, auto_activate, sort_order) VALUES (?, ?, ?, NULL, 0);",
            arrayOf<Any>("00000000-0000-0000-0000-000000000001", "默認", 0),
        )
        db.execSQL(
            """
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
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX ix_rules_profile ON rules(profile_id);")
        db.execSQL(
            """
            CREATE TABLE rule_history (
                id         TEXT    PRIMARY KEY,
                snapshot   TEXT    NOT NULL,
                ended_at   INTEGER NOT NULL,
                end_reason INTEGER NOT NULL
            );
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX ix_rule_history_ended ON rule_history(ended_at);")
        db.execSQL(
            """
            CREATE TABLE settings (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 目前只有 v1。後續遷移按版本追加，不修改已發布的 schema。
    }

    companion object {
        const val NAME = "config.db"
        const val VERSION = 1
    }
}

class ActivityDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE connection_log (
                id          INTEGER PRIMARY KEY,
                minute_ts   INTEGER NOT NULL,
                app_id      INTEGER NOT NULL DEFAULT -1,
                app_path    TEXT,
                app_name    TEXT,
                direction   INTEGER NOT NULL,
                protocol    INTEGER NOT NULL,
                remote_ip   TEXT    NOT NULL,
                remote_port INTEGER NOT NULL,
                hostname    TEXT,
                verdict     INTEGER NOT NULL,
                reason      INTEGER NOT NULL,
                rule_id     TEXT,
                count       INTEGER NOT NULL DEFAULT 1,
                first_ts    INTEGER NOT NULL,
                last_ts     INTEGER NOT NULL,
                local_ip    TEXT,
                local_port  INTEGER
            );
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX ux_log_agg
            ON connection_log(minute_ts, app_id, ifnull(app_path, ''), direction, protocol, remote_ip, remote_port, verdict, reason);
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX ix_log_last_ts ON connection_log(last_ts);")
        createTrafficTables(db)
        createTrafficNetworkTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP INDEX IF EXISTS ux_log_agg;")
            db.execSQL(
                """
                CREATE UNIQUE INDEX ux_log_agg
                ON connection_log(minute_ts, app_id, ifnull(app_path, ''), direction, protocol, remote_ip, remote_port, verdict, reason);
                """.trimIndent(),
            )
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE connection_log ADD COLUMN local_ip TEXT;")
            db.execSQL("ALTER TABLE connection_log ADD COLUMN local_port INTEGER;")
        }
        if (oldVersion < VERSION && !tableExists(db, "traffic_minute")) {
            createTrafficTables(db)
        }
        if (oldVersion < 4) {
            createTrafficNetworkTable(db)
        }
    }

    private fun createTrafficTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS traffic_minute (app_id INTEGER NOT NULL, ts INTEGER NOT NULL, bytes_in INTEGER NOT NULL, bytes_out INTEGER NOT NULL, PRIMARY KEY (app_id, ts)) WITHOUT ROWID;",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS traffic_hour (app_id INTEGER NOT NULL, ts INTEGER NOT NULL, bytes_in INTEGER NOT NULL, bytes_out INTEGER NOT NULL, PRIMARY KEY (app_id, ts)) WITHOUT ROWID;",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS traffic_day (app_id INTEGER NOT NULL, ts INTEGER NOT NULL, bytes_in INTEGER NOT NULL, bytes_out INTEGER NOT NULL, PRIMARY KEY (app_id, ts)) WITHOUT ROWID;",
        )
    }

    private fun createTrafficNetworkTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS traffic_network (
                app_id          INTEGER NOT NULL,
                ts              INTEGER NOT NULL,
                unit            INTEGER NOT NULL,
                network_kind    INTEGER NOT NULL,
                subscription_id INTEGER NOT NULL DEFAULT -1,
                bytes_in        INTEGER NOT NULL,
                bytes_out       INTEGER NOT NULL,
                PRIMARY KEY (app_id, ts, unit, network_kind, subscription_id)
            ) WITHOUT ROWID;
            """.trimIndent(),
        )
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?;", arrayOf(name)).use { it.moveToFirst() }

    companion object {
        const val NAME = "activity.db"
        const val VERSION = 4
    }
}
