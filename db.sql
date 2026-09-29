CREATE DATABASE IF NOT EXISTS db_item DEFAULT CHARACTER SET utf8mb4;
USE db_item;

-- 商品表
CREATE TABLE `t_item` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `item_name` varchar(100) NOT NULL COMMENT '虚拟饰品名称',
    `price` decimal(10,2) NOT NULL COMMENT '价格',
    `stock` int(11) NOT NULL DEFAULT '0' COMMENT '真实库存',
    `frozen_stock` int(11) NOT NULL DEFAULT '0' COMMENT 'TCC预留库存(冻结)',
    `version` int(11) NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
    `category_id` bigint(20) DEFAULT 1 COMMENT '分类ID (1:武器箱 2:手套 3:匕首 4:枪械)',
    `sub_title` varchar(255) DEFAULT '' COMMENT '商品副标题/磨损特征描述',
    `image_url` varchar(512) DEFAULT '' COMMENT '商品主展示图URL',
    `detail_html` text COMMENT '商品图文详情',
    `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '商品状态: 1-上架, 0-下架',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- 秒杀活动场次表
CREATE TABLE IF NOT EXISTS `t_seckill_session` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '场次主键ID',
    `session_name` varchar(64) NOT NULL COMMENT '场次名称',
    `start_time` datetime NOT NULL COMMENT '场次开始时间',
    `end_time` datetime NOT NULL COMMENT '场次结束时间',
    `status` tinyint(4) NOT NULL DEFAULT 0 COMMENT '场次状态: 0-预告/预热中, 1-进行中, 2-已下线/已结束',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_session_time` (`start_time`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动场次表';

-- 秒杀活动商品关联配置表
CREATE TABLE IF NOT EXISTS `t_seckill_item` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `session_id` bigint(20) NOT NULL COMMENT '所属场次ID',
    `item_id` bigint(20) NOT NULL COMMENT '关联基础商品ID',
    `seckill_price` decimal(10,2) NOT NULL COMMENT '秒杀专享价',
    `seckill_stock` int(11) NOT NULL DEFAULT 0 COMMENT '秒杀配额总库存',
    `remain_stock` int(11) NOT NULL DEFAULT 0 COMMENT '秒杀剩余库存',
    `limit_per_user` int(11) NOT NULL DEFAULT 1 COMMENT '单用户限购数量',
    `sort_order` int(11) NOT NULL DEFAULT 0 COMMENT '首页展示排序权重',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_item` (`session_id`, `item_id`),
    KEY `idx_item_id` (`item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀商品关系配置表';

-- 首页轮播配置表
CREATE TABLE IF NOT EXISTS `t_banner` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `title` varchar(128) NOT NULL COMMENT 'Banner标题',
    `image_url` varchar(512) NOT NULL COMMENT '海报图片URL',
    `target_url` varchar(512) DEFAULT NULL COMMENT '点击跳转地址',
    `badge_text` varchar(32) DEFAULT NULL COMMENT '左上角角标',
    `sort_order` int(11) NOT NULL DEFAULT 0 COMMENT '排序权重',
    `is_active` tinyint(4) NOT NULL DEFAULT 1 COMMENT '是否展示: 1-启用, 0-禁用',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='首页轮播配置表';

-- TCC 事务控制表 (简历亮点：处理空回滚、幂等、悬挂)
CREATE TABLE `t_tcc_action_log` (
    `tx_id` varchar(128) NOT NULL COMMENT '全局事务ID (Seata XID)',
    `branch_id` varchar(128) NOT NULL COMMENT '分支事务ID',
    `action_name` varchar(64) NOT NULL COMMENT '操作名称(如: deduct_stock)',
    `status` tinyint(4) NOT NULL COMMENT '状态: 0-Try中, 1-Try成功, 2-Confirm成功, 3-Cancel成功, 4-防悬挂记录',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`tx_id`,`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TCC本地事务控制表';

CREATE DATABASE IF NOT EXISTS db_order DEFAULT CHARACTER SET utf8mb4;
USE db_order;

-- 订单表
CREATE TABLE `t_order` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `order_no` varchar(64) NOT NULL COMMENT '订单号',
    `user_id` bigint(20) NOT NULL,
    `item_id` bigint(20) NOT NULL,
    `pay_amount` decimal(10,2) NOT NULL,
    `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '0-新建, 1-已支付, 2-已取消',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`) -- 简历亮点：数据库唯一索引双重校验杜绝重复创单
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- RocketMQ 本地事务消息日志表 (简历亮点：RocketMQ 半消息及本地事务回查)
CREATE TABLE `t_mq_transaction_log` (
    `transaction_id` varchar(64) NOT NULL COMMENT 'MQ事务ID',
    `order_no` varchar(64) NOT NULL COMMENT '关联的订单号',
    `status` tinyint(4) NOT NULL COMMENT '状态: 0-处理中, 1-成功, 2-失败',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`transaction_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RocketMQ本地事务日志表';

-- 对账差异表 (简历亮点：自动筛查并入库单边账、金额不一致等异常差错数据)
CREATE TABLE `t_reconciliation_diff` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `order_no` varchar(64) NOT NULL COMMENT '平台订单号',
    `trade_no` varchar(64) DEFAULT NULL COMMENT '支付渠道流水号',
    `diff_type` tinyint(4) NOT NULL COMMENT '差异类型: 1-平台单边账(掉单), 2-渠道单边账, 3-金额不一致',
    `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '处理状态: 0-待处理, 1-已人工介入',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差异异常表';

-- 操作审计日志表 (简历亮点：Spring AOP + 自定义注解提取，支持 RocketMQ 异步解耦削峰与同步落库)
CREATE TABLE `t_audit_log` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '日志主键ID',
    `trace_id` varchar(64) DEFAULT NULL COMMENT '全链路追踪ID(TraceId)',
    `title` varchar(100) NOT NULL COMMENT '模块/操作名称',
    `business_type` varchar(32) NOT NULL DEFAULT 'OTHER' COMMENT '业务操作类型(INSERT, UPDATE, DELETE, SELECT, EXPORT, OTHER)',
    `method` varchar(256) DEFAULT NULL COMMENT '方法名称(类全路径+方法名)',
    `request_method` varchar(16) DEFAULT NULL COMMENT 'HTTP请求方式(GET, POST等)',
    `operator_id` bigint(20) DEFAULT NULL COMMENT '操作人ID',
    `operator_name` varchar(64) DEFAULT NULL COMMENT '操作人姓名/账号',
    `operator_url` varchar(256) DEFAULT NULL COMMENT '请求URL',
    `operator_ip` varchar(64) DEFAULT NULL COMMENT '客户端IP',
    `request_params` text COMMENT '请求参数(JSON格式)',
    `response_result` text COMMENT '返回结果(JSON格式)',
    `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '操作状态: 0-成功, 1-失败',
    `error_msg` text COMMENT '错误/异常信息',
    `cost_time` bigint(20) NOT NULL DEFAULT '0' COMMENT '方法执行耗时(毫秒)',
    `delivery_mode` varchar(32) NOT NULL DEFAULT 'ASYNC_MQ' COMMENT '投递方式: ASYNC_MQ(RocketMQ异步), SYNC_DB(同步入库)',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY `idx_trace_id` (`trace_id`),
    KEY `idx_operator_id` (`operator_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志表';

CREATE DATABASE IF NOT EXISTS db_user DEFAULT CHARACTER SET utf8mb4;
USE db_user;

CREATE TABLE `t_user` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `email` varchar(64) NOT NULL COMMENT '用户邮箱',
    `password` varchar(128) NOT NULL,
    `nickname` varchar(64) DEFAULT 'VALOR特工' COMMENT '用户昵称',
    `avatar_url` varchar(512) DEFAULT 'https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=200&q=80' COMMENT '用户头像',
    `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用户积分账户总表
CREATE TABLE IF NOT EXISTS `t_user_point` (
    `user_id` bigint(20) NOT NULL COMMENT '用户主键ID',
    `total_points` int(11) NOT NULL DEFAULT 0 COMMENT '当前有效可用积分',
    `history_earned_points` int(11) NOT NULL DEFAULT 0 COMMENT '历史累计获得积分',
    `version` int(11) NOT NULL DEFAULT 0 COMMENT '乐观锁版本控制',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户积分账户资产表';

-- 积分动账流水记录表 (简历核心亮点：唯一键防重与财务合规审计)
CREATE TABLE IF NOT EXISTS `t_point_record` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '明细主键ID',
    `user_id` bigint(20) NOT NULL COMMENT '用户ID',
    `order_no` varchar(64) NOT NULL COMMENT '关联业务订单号',
    `change_points` int(11) NOT NULL COMMENT '本次变动积分值',
    `balance_after` int(11) NOT NULL COMMENT '变动后的账户总可用积分',
    `change_type` tinyint(4) NOT NULL COMMENT '动账业务类型: 1-订单消费返利, 2-退款撤销扣减, 3-积分商城兑换消耗, 4-系统运营调账',
    `remark` varchar(255) DEFAULT '' COMMENT '动账业务说明',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_type` (`order_no`, `change_type`),
    KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户积分动账流水表';