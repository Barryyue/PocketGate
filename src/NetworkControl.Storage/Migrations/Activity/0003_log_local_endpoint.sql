-- 聚合日誌記錄該分鐘內最近一次連線的本地端點；本地端口不加入聚合鍵。
ALTER TABLE connection_log ADD COLUMN local_ip TEXT;
ALTER TABLE connection_log ADD COLUMN local_port INTEGER;
