package api.audit;

import java.lang.annotation.*;

/**
 * 自定义操作审计日志注解
 * 
 * 作用在 Controller 或 Service 方法上，配合 Spring AOP 自动提取操作人、IP、请求参数、返回结果及执行耗时。
 * 支持通过 RocketMQ 异步投递或同步写库。
 */
@Target({ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /**
     * 模块或操作名称（如："创建订单"、"商品预扣库存"）
     */
    String title() default "";

    /**
     * 业务操作类型
     */
    BusinessType businessType() default BusinessType.OTHER;

    /**
     * 是否保存请求参数
     */
    boolean isSaveRequestData() default true;

    /**
     * 是否保存响应数据
     */
    boolean isSaveResponseData() default true;

    /**
     * 指定投递方式：
     * DEFAULT: 遵循全局配置 (audit.delivery-mode)
     * ASYNC_MQ: 强制走 RocketMQ 异步解耦投递
     * SYNC_DB: 强制走同步落库
     */
    DeliveryMode deliveryMode() default DeliveryMode.DEFAULT;

    /**
     * 需要排除/不记录的敏感参数名称（例如 password, token, creditCard）
     */
    String[] excludeParamNames() default {"password", "token", "accessToken"};
}
