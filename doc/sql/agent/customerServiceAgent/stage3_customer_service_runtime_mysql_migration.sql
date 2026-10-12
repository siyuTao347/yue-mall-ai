-- 客服实现补充：跨进程用户并发租约。先执行已有客服基础表和意图发布脚本。
-- 只新建表，不修改已执行脚本；应用启动不自动执行 DDL。
USE db_agent;
CREATE TABLE IF NOT EXISTS t_agent_user_gate (
    user_id bigint unsigned NOT NULL,
    lease_run_no varchar(64) DEFAULT NULL,
    lease_until datetime(3) DEFAULT NULL,
    PRIMARY KEY(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服每用户运行租约，短事务锁定，超时可恢复';
