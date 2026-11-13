-- 聚合鍵加入應用路徑：未識別的應用（app_id = -1）原來按 (分鐘, -1, 目標, ...) 合併，
-- 不同程序連接同一目標時會被記到第一個程序名下。舊索引的每一組在新索引下仍然唯一，無需改動數據。
DROP INDEX ux_log_agg;
CREATE UNIQUE INDEX ux_log_agg ON connection_log(minute_ts, app_id, ifnull(app_path, ''), direction, protocol, remote_ip, remote_port, verdict, reason);
