package com.example.item.audit;

import api.audit.AuditLog;
import api.audit.AuditLogDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 操作审计日志 AOP 切面
 *
 * 【简历核心亮点】：
 * 1. 采用 Spring AOP 环绕通知 (@Around) 无侵入式采集接口调用元数据（操作人、IP、入参、出参、异常、耗时）。
 * 2. 自动捕获全链路 TraceId，并在缺少时自动生成注入 MDC，确保日志具备分布式环境可追溯性。
 * 3. 敏感信息过滤与安全防护：智能过滤不可序列化参数 (ServletRequest/Response) 及敏感密码字段。
 * 4. 最终交由策略分发器 (AuditLogDispatcher)，按需路由至 RocketMQ 异步解耦通道或同步写库通道。
 */
@Slf4j
@Aspect
@Order(1) // 优先级较高，确保能完整包裹业务逻辑与事务
@Component
public class AuditLogAspect {

    @Autowired
    private AuditLogDispatcher auditLogDispatcher;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String TRACE_ID_KEY = "traceId";

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        long startTime = System.currentTimeMillis();

        // 1. 获取或生成 TraceId
        String traceId = MDC.get(TRACE_ID_KEY);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
            MDC.put(TRACE_ID_KEY, traceId);
        }

        AuditLogDTO logDTO = new AuditLogDTO();
        logDTO.setTraceId(traceId);
        logDTO.setTitle(auditLog.title());
        logDTO.setBusinessType(auditLog.businessType().name());
        logDTO.setOperateTime(new Date());

        // 2. 提取方法全名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        logDTO.setMethod(signature.getDeclaringTypeName() + "." + method.getName() + "()");

        // 3. 提取 HTTP 请求上下文 (IP, URL, Method, Header 操作人等)
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            logDTO.setRequestMethod(request.getMethod());
            logDTO.setOperatorUrl(request.getRequestURI());
            logDTO.setOperatorIp(getClientIp(request));

            // 尝试从 Header 或请求参数提取操作人
            extractOperatorInfo(request, joinPoint, logDTO);
        }

        // 4. 提取并序列化请求参数
        if (auditLog.isSaveRequestData()) {
            extractRequestParams(joinPoint, signature, auditLog, logDTO);
        }

        Object result = null;
        try {
            // 5. 放行目标方法执行
            result = joinPoint.proceed();

            // 正常执行完成
            logDTO.setStatus(0); // 0-正常
            if (auditLog.isSaveResponseData() && result != null) {
                try {
                    logDTO.setResponseResult(objectMapper.writeValueAsString(result));
                } catch (Exception e) {
                    logDTO.setResponseResult(String.valueOf(result));
                }
            }
            return result;

        } catch (Throwable e) {
            // 异常捕获记录
            logDTO.setStatus(1); // 1-失败
            logDTO.setErrorMsg(getExceptionSummary(e));
            throw e; // 继续向上抛出业务异常，保证事务正确回滚

        } finally {
            // 6. 计算执行耗时
            long costTime = System.currentTimeMillis() - startTime;
            logDTO.setCostTime(costTime);

            // 7. 分发投递日志 (异步 RocketMQ 或 同步落库)
            try {
                auditLogDispatcher.dispatch(logDTO, auditLog.deliveryMode());
            } catch (Exception e) {
                log.error("【审计日志分发出现非预期异常】TraceId: {}, Error: {}", logDTO.getTraceId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 提取操作人信息
     */
    private void extractOperatorInfo(HttpServletRequest request, ProceedingJoinPoint joinPoint, AuditLogDTO logDTO) {
        String userIdStr = request.getHeader("X-User-Id");
        if (!StringUtils.hasText(userIdStr)) {
            userIdStr = request.getParameter("userId");
        }
        if (StringUtils.hasText(userIdStr)) {
            try {
                logDTO.setOperatorId(Long.valueOf(userIdStr));
            } catch (NumberFormatException ignored) {
            }
        }

        String username = request.getHeader("X-User-Name");
        if (!StringUtils.hasText(username)) {
            username = request.getParameter("username");
        }
        logDTO.setOperatorName(StringUtils.hasText(username) ? username : "SystemUser");
    }

    private static final org.springframework.core.ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
            new org.springframework.core.DefaultParameterNameDiscoverer();

    /**
     * 提取请求参数并过滤敏感字段
     */
    private void extractRequestParams(ProceedingJoinPoint joinPoint, MethodSignature signature,
                                      AuditLog auditLog, AuditLogDTO logDTO) {
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return;
        }

        String[] paramNames = signature.getParameterNames();
        if (paramNames == null || paramNames.length == 0 || (paramNames.length > 0 && paramNames[0].startsWith("arg"))) {
            String[] discovered = PARAMETER_NAME_DISCOVERER.getParameterNames(signature.getMethod());
            if (discovered != null && discovered.length == args.length) {
                paramNames = discovered;
            }
        }

        Set<String> excludeSet = new HashSet<>();
        if (auditLog.excludeParamNames() != null) {
            for (String s : auditLog.excludeParamNames()) {
                if (s != null && !s.trim().isEmpty()) {
                    excludeSet.add(s.trim().toLowerCase());
                }
            }
        }

        Map<String, Object> paramMap = new HashMap<>();

        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            // 过滤不可直接序列化的 HTTP 容器类与大文件
            if (arg instanceof HttpServletRequest || arg instanceof HttpServletResponse
                    || arg instanceof MultipartFile) {
                continue;
            }

            String paramName = (paramNames != null && paramNames.length > i) ? paramNames[i] : ("arg" + i);
            boolean isSensitive = false;
            String lowerName = paramName.toLowerCase();
            for (String exc : excludeSet) {
                if (lowerName.contains(exc)) {
                    isSensitive = true;
                    break;
                }
            }

            if (isSensitive) {
                paramMap.put(paramName, "******(已脱敏)");
            } else {
                paramMap.put(paramName, arg);
            }
        }


        try {
            logDTO.setRequestParams(objectMapper.writeValueAsString(paramMap));
        } catch (Exception e) {
            logDTO.setRequestParams(Arrays.toString(args));
        }
    }

    /**
     * 提取客户端真实 IP 地址
     */
    private String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String ip = request.getHeader("x-forwarded-for");
        if (!isValidIp(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (!isValidIp(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (!isValidIp(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (!isValidIp(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (!isValidIp(ip)) {
            ip = request.getRemoteAddr();
        }
        if ("0:0:0:0:0:0:0:1".equals(ip)) {
            ip = "127.0.0.1";
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : "unknown";
    }

    private boolean isValidIp(String ip) {
        return StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip.trim());
    }

    /**
     * 提取异常堆栈摘要（避免超大堆栈打爆数据库 TEXT 列）
     */
    private String getExceptionSummary(Throwable e) {
        if (e == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String stackTrace = sw.toString();
        // 限制最大长度 2000 个字符
        return stackTrace.length() > 2000 ? stackTrace.substring(0, 2000) + "..." : stackTrace;
    }
}
