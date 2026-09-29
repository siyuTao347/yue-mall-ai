package api.audit;

/**
 * 审计日志投递模式枚举
 */
public enum DeliveryMode {
    /**
     * 默认模式：遵循全局配置文件中的 audit.delivery-mode 配置
     */
    DEFAULT,

    /**
     * RocketMQ 异步投递：解耦核心业务，降低响应延迟，削峰填谷
     */
    ASYNC_MQ,

    /**
     * 同步直接落库：业务线程同步执行数据库插入
     */
    SYNC_DB
}
