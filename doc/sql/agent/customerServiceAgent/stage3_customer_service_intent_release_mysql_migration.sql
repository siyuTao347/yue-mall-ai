-- 智能客服意图 RAG —— 完整版本发布增量迁移（MySQL）
-- 对应 implementation_design.md 第 7.1、10.8、12 节。
-- 适用：已执行 stage3_customer_service_intent_mysql_migration.sql 的环境。
-- 本脚本只新增发布表；不改写已执行脚本，不删除/更新旧意图、示例、向量或 Agent JSON。
-- 多意图槽位复用现有 JSON 字段，schemaVersion=2，无需 ALTER 或批量 JSON 数据迁移。
-- 执行顺序：本脚本 -> 同目录 stage3_customer_service_intent_release_pgvector_migration.sql。
-- 执行示例：mysql --default-character-set=utf8mb4 -u <user> -p db_agent < <本文件路径>
-- 重复执行不会重置活动指针；IF NOT EXISTS 不负责修复不同结构的同名表。
-- MySQL DDL 隐式提交，表创建完成后再初始化指针；不在本脚本中发布任何版本。

USE `db_agent`;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `t_customer_service_intent_release` (
    `release_no` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `status` varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, BUILDING, VALIDATING, READY, FAILED, REVOKED',
    `embedding_model` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `embedding_dim` int NOT NULL DEFAULT 1536,
    `default_similarity_threshold` decimal(5,4) NOT NULL DEFAULT 0.8800,
    `min_margin` decimal(5,4) NOT NULL DEFAULT 0.0500,
    `expected_intent_count` int NOT NULL DEFAULT 0 COMMENT '完整业务范围，不按 READY 进度缩小；发布至少 2 个意图',
    `expected_example_count` int NOT NULL DEFAULT 0 COMMENT '冻结清单中所有示例数',
    `manifest_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '冻结配置、意图和示例完整快照的 SHA256',
    `validation_report_json` json DEFAULT NULL COMMENT '清单/PG 数量、逐项校验、评估集版本和结论',
    `validated_time` datetime(3) DEFAULT NULL,
    `created_by` bigint unsigned DEFAULT NULL,
    `created_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`release_no`),
    KEY `idx_cs_release_status_time` (`status`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服意图库发布头；READY 仅表示完整验证通过，活动状态由指针确定';

CREATE TABLE IF NOT EXISTS `t_customer_service_intent_release_item` (
    `release_no` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `intent_code` varchar(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `intent_version` int NOT NULL,
    `intent_name` varchar(64) NOT NULL,
    `description` varchar(512) NOT NULL,
    `route_node` varchar(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `required_slots_json` json NOT NULL,
    `similarity_threshold` decimal(5,4) NOT NULL COMMENT '已解析意图专属值或发布默认值，不在运行时读取编辑表',
    `expected_example_count` int NOT NULL COMMENT '本意图完整示例数，冻结前验证至少 1 条',
    PRIMARY KEY (`release_no`, `intent_code`),
    CONSTRAINT `fk_cs_release_item_header` FOREIGN KEY (`release_no`)
        REFERENCES `t_customer_service_intent_release` (`release_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布意图定义快照，BUILDING 起不可修改';

CREATE TABLE IF NOT EXISTS `t_customer_service_intent_release_example` (
    `release_no` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `example_no` varchar(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `example_version` int NOT NULL,
    `intent_code` varchar(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `intent_version` int NOT NULL,
    `example_text` varchar(512) NOT NULL,
    `normalized_text` varchar(512) NOT NULL,
    `example_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `embedding_input_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA256(本版模型 + | + normalized_text)',
    PRIMARY KEY (`release_no`, `example_no`),
    KEY `idx_cs_release_example_intent` (`release_no`, `intent_code`),
    CONSTRAINT `fk_cs_release_example_item` FOREIGN KEY (`release_no`, `intent_code`)
        REFERENCES `t_customer_service_intent_release_item` (`release_no`, `intent_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='完整发布清单；同版每个示例只选一个版本，不按向量 READY 过滤';

CREATE TABLE IF NOT EXISTS `t_customer_service_intent_active_release` (
    `scene` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `active_release_no` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'NULL 表示 L2 未发布，进入 L3',
    `lock_version` bigint unsigned NOT NULL DEFAULT 0,
    `updated_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`scene`),
    CONSTRAINT `fk_cs_active_release_header` FOREIGN KEY (`active_release_no`)
        REFERENCES `t_customer_service_intent_release` (`release_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场景唯一活动指针；在事务内整版切换';

INSERT INTO `t_customer_service_intent_active_release` (`scene`, `active_release_no`)
VALUES ('CUSTOMER_SERVICE', NULL)
ON DUPLICATE KEY UPDATE `scene` = `scene`;

-- 应用发布约束（DDL 不会自动完成发布，必须实现以下流程）：
-- 1. DRAFT 中从编辑源复制完整范围及所有启用/版本一致的示例，不过滤 embedding_status。
--    冻结前校验：至少 2 个意图、每个意图至少 1 条示例、同一 example_no 仅一个启用版本；
--    example/intent 版本为正、阈值及 min_margin 在 [0,1]、维度为 1536、全部 hash 正确。
-- 2. 清单数量、各意图数量、manifest_hash、模型和阈值与快照同事务冻结，再进入 BUILDING。
--    BUILDING 起快照禁止改动；不得依据成功任务数量反向修改 expected_*。
-- 3. 固定 release_no 构建 PG 向量，完成后冻结写入进入 VALIDATING，再全量比对和评估。
--    成功才标记 READY 并保存 validation_report_json / validated_time。
-- 4. 发布操作必须由服务端在完成跨库校验后执行短事务：
--    SELECT * FROM t_customer_service_intent_active_release
--     WHERE scene='CUSTOMER_SERVICE' FOR UPDATE;
--    SELECT * FROM t_customer_service_intent_release WHERE release_no=? FOR UPDATE;
--    检查候选 READY、报告与清单 hash 一致，然后 UPDATE 指针并 lock_version=lock_version+1。
--    事务内顺序统一为先锁指针、后锁发布头；回滚/撤销操作遵守相同顺序。
-- 5. 不能仅因为 PG 有一部分向量就发布；READY 不等于当前活动，可保留多个 READY 版本回滚。
-- 6. 普通编辑只影响后续版本；紧急禁用须 REVOKED 相关发布版本并清空活动指针，同一事务提交。
--    在线请求路由前检查未撤销，版本不完整/被撤销即整次 L3。
-- 7. 编辑表旧 embedding_status 字段保留兼容，但不再决定新 L2 在线可用集合。
-- 8. 迁移旧向量须逐条验证后复制到新发布 PG 表，再按完整版本流程校验；本脚本不自动信任旧数据。

-- 执行后检查：首次迁移活动指针为空，重复执行应保持已有指针不变。
SELECT `scene`, `active_release_no`, `lock_version`
  FROM `t_customer_service_intent_active_release`
 WHERE `scene` = 'CUSTOMER_SERVICE';

-- 以下是发布校验的辅助查询，不代替 hash、模型、完整范围或 PG 的逐项校验：
-- SELECT r.release_no, r.expected_intent_count, COUNT(i.intent_code) AS actual_intent_count
--   FROM t_customer_service_intent_release r
--   LEFT JOIN t_customer_service_intent_release_item i ON i.release_no=r.release_no
--  GROUP BY r.release_no, r.expected_intent_count;
-- SELECT i.release_no, i.intent_code, i.expected_example_count, COUNT(e.example_no) AS actual_examples
--   FROM t_customer_service_intent_release_item i
--   LEFT JOIN t_customer_service_intent_release_example e
--     ON e.release_no=i.release_no AND e.intent_code=i.intent_code
--  GROUP BY i.release_no, i.intent_code, i.expected_example_count
-- HAVING actual_examples <> i.expected_example_count OR actual_examples = 0;
