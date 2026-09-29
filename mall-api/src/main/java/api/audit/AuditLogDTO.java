package api.audit;

import java.io.Serializable;
import java.util.Date;

/**
 * 审计日志数据传输对象 (DTO)
 * 
 * 用于在 AOP 提取层、RocketMQ 消息总线与持久化层之间传递审计日志元数据。
 */
public class AuditLogDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 全链路追踪ID (TraceId)
     */
    private String traceId;

    /**
     * 模块 / 操作标题
     */
    private String title;

    /**
     * 业务操作类型 (INSERT, UPDATE, DELETE, etc.)
     */
    private String businessType;

    /**
     * 方法全路径 (类名.方法名)
     */
    private String method;

    /**
     * HTTP请求方式 (GET, POST, PUT, DELETE)
     */
    private String requestMethod;

    /**
     * 操作人ID
     */
    private Long operatorId;

    /**
     * 操作人姓名/账号
     */
    private String operatorName;

    /**
     * 请求 URL
     */
    private String operatorUrl;

    /**
     * 客户端真实 IP
     */
    private String operatorIp;

    /**
     * 请求参数 (JSON字符串)
     */
    private String requestParams;

    /**
     * 响应结果 (JSON字符串)
     */
    private String responseResult;

    /**
     * 操作状态 (0: 正常/成功, 1: 异常/失败)
     */
    private Integer status;

    /**
     * 错误信息 / 异常堆栈摘要
     */
    private String errorMsg;

    /**
     * 接口执行耗时 (毫秒)
     */
    private Long costTime;

    /**
     * 投递模式 (ASYNC_MQ / SYNC_DB)
     */
    private String deliveryMode;

    /**
     * 操作发生时间
     */
    private Date operateTime;

    public AuditLogDTO() {
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBusinessType() {
        return businessType;
    }

    public void setBusinessType(String businessType) {
        this.businessType = businessType;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public void setRequestMethod(String requestMethod) {
        this.requestMethod = requestMethod;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public void setOperatorName(String operatorName) {
        this.operatorName = operatorName;
    }

    public String getOperatorUrl() {
        return operatorUrl;
    }

    public void setOperatorUrl(String operatorUrl) {
        this.operatorUrl = operatorUrl;
    }

    public String getOperatorIp() {
        return operatorIp;
    }

    public void setOperatorIp(String operatorIp) {
        this.operatorIp = operatorIp;
    }

    public String getRequestParams() {
        return requestParams;
    }

    public void setRequestParams(String requestParams) {
        this.requestParams = requestParams;
    }

    public String getResponseResult() {
        return responseResult;
    }

    public void setResponseResult(String responseResult) {
        this.responseResult = responseResult;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Long getCostTime() {
        return costTime;
    }

    public void setCostTime(Long costTime) {
        this.costTime = costTime;
    }

    public String getDeliveryMode() {
        return deliveryMode;
    }

    public void setDeliveryMode(String deliveryMode) {
        this.deliveryMode = deliveryMode;
    }

    public Date getOperateTime() {
        return operateTime;
    }

    public void setOperateTime(Date operateTime) {
        this.operateTime = operateTime;
    }

    @Override
    public String toString() {
        return "AuditLogDTO{" +
                "traceId='" + traceId + '\'' +
                ", title='" + title + '\'' +
                ", businessType='" + businessType + '\'' +
                ", method='" + method + '\'' +
                ", requestMethod='" + requestMethod + '\'' +
                ", operatorId=" + operatorId +
                ", operatorName='" + operatorName + '\'' +
                ", operatorUrl='" + operatorUrl + '\'' +
                ", operatorIp='" + operatorIp + '\'' +
                ", status=" + status +
                ", costTime=" + costTime +
                ", deliveryMode='" + deliveryMode + '\'' +
                ", operateTime=" + operateTime +
                '}';
    }
}
