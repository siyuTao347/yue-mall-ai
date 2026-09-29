package com.example.item.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 操作审计日志实体类，对应表 t_audit_log
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_audit_log")
public class AuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 日志主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 全链路追踪ID (TraceId)
     */
    private String traceId;

    /**
     * 模块 / 操作名称
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
     * HTTP请求方式 (GET, POST, etc.)
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
     * 客户端IP
     */
    private String operatorIp;

    /**
     * 请求参数 (JSON格式)
     */
    private String requestParams;

    /**
     * 返回结果 (JSON格式)
     */
    private String responseResult;

    /**
     * 操作状态: 0-成功, 1-失败
     */
    private Integer status;

    /**
     * 错误/异常堆栈信息
     */
    private String errorMsg;

    /**
     * 方法执行耗时 (毫秒)
     */
    private Long costTime;

    /**
     * 投递方式: ASYNC_MQ / SYNC_DB
     */
    private String deliveryMode;

    /**
     * 操作时间
     */
    private Date createTime;
}
