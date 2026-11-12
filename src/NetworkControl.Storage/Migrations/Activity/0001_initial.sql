-- activity.db 初始結構（設計文檔 §13.3）
-- 連接日誌按分鐘聚合：同一分鐘內的相同連接只保留一行並累加 count。

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
    last_ts     INTEGER NOT NULL
);

CREATE UNIQUE INDEX ux_log_agg ON connection_log(minute_ts, app_id, direction, protocol, remote_ip, remote_port, verdict, reason);
CREATE INDEX ix_log_last_ts ON connection_log(last_ts);

CREATE TABLE traffic_minute (app_id INTEGER NOT NULL, ts INTEGER NOT NULL, bytes_in INTEGER NOT NULL, bytes_out INTEGER NOT NULL, PRIMARY KEY (app_id, ts)) WITHOUT ROWID;
CREATE TABLE traffic_hour   (app_id INTEGER NOT NULL, ts INTEGER NOT NULL, bytes_in INTEGER NOT NULL, bytes_out INTEGER NOT NULL, PRIMARY KEY (app_id, ts)) WITHOUT ROWID;
CREATE TABLE traffic_day    (app_id INTEGER NOT NULL, ts INTEGER NOT NULL, bytes_in INTEGER NOT NULL, bytes_out INTEGER NOT NULL, PRIMARY KEY (app_id, ts)) WITHOUT ROWID;
