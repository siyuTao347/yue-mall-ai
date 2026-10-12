-- Seata 2.5.0 AT 模式业务库补表；在启动 User / Item / Order 服务前执行。
-- 基础库须已存在。可重复执行，只创建缺失的表，不删除或修改已有数据。
-- 表结构参考：https://github.com/apache/incubator-seata/blob/v2.5.0/script/client/at/db/mysql.sql
-- 将清理索引合并进 CREATE TABLE，避免重复执行 ALTER TABLE ADD INDEX 报错。
-- undo_log 必须建在各业务数据源所在库，而非仅建在 Seata Server 库。

CREATE TABLE IF NOT EXISTS db_item.undo_log (
    branch_id BIGINT NOT NULL,
    xid VARCHAR(128) NOT NULL,
    context VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB NOT NULL,
    log_status INT NOT NULL,
    log_created DATETIME(6) NOT NULL,
    log_modified DATETIME(6) NOT NULL,
    UNIQUE KEY ux_undo_log (xid, branch_id),
    KEY ix_log_created (log_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AT transaction mode undo table';

CREATE TABLE IF NOT EXISTS db_user.undo_log (
    branch_id BIGINT NOT NULL,
    xid VARCHAR(128) NOT NULL,
    context VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB NOT NULL,
    log_status INT NOT NULL,
    log_created DATETIME(6) NOT NULL,
    log_modified DATETIME(6) NOT NULL,
    UNIQUE KEY ux_undo_log (xid, branch_id),
    KEY ix_log_created (log_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AT transaction mode undo table';

CREATE TABLE IF NOT EXISTS db_order.undo_log (
    branch_id BIGINT NOT NULL,
    xid VARCHAR(128) NOT NULL,
    context VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB NOT NULL,
    log_status INT NOT NULL,
    log_created DATETIME(6) NOT NULL,
    log_modified DATETIME(6) NOT NULL,
    UNIQUE KEY ux_undo_log (xid, branch_id),
    KEY ix_log_created (log_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AT transaction mode undo table';
