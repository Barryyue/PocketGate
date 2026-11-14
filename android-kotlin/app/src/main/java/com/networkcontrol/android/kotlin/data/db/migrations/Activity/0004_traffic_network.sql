-- Android Kotlin 版：按網路類型 / SIM 拆分的流量表。
-- 總量表 traffic_minute / traffic_hour / traffic_day 不變；這張表只存拆分。
CREATE TABLE IF NOT EXISTS traffic_network (
    app_id          INTEGER NOT NULL,
    ts              INTEGER NOT NULL,
    unit            INTEGER NOT NULL, -- 60000 / 3600000 / 86400000
    network_kind    INTEGER NOT NULL, -- NetworkKind.code
    subscription_id INTEGER NOT NULL DEFAULT -1,
    bytes_in        INTEGER NOT NULL,
    bytes_out       INTEGER NOT NULL,
    PRIMARY KEY (app_id, ts, unit, network_kind, subscription_id)
) WITHOUT ROWID;
