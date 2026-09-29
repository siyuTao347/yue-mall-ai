package com.example.item.audit;

import api.audit.AuditLogDTO;
import api.audit.BusinessType;
import api.audit.DeliveryMode;
import com.example.item.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 审计日志核心逻辑单元测试
 */
public class AuditLogTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private ObjectMapper objectMapper = new ObjectMapper();

    private SyncDbAuditLogSender syncSender;
    private RocketMQAsyncAuditLogSender asyncSender;
    private AuditLogDispatcher dispatcher;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        syncSender = new SyncDbAuditLogSender();
        ReflectionTestUtils.setField(syncSender, "auditLogService", auditLogService);

        asyncSender = new RocketMQAsyncAuditLogSender();
        ReflectionTestUtils.setField(asyncSender, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(asyncSender, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(asyncSender, "topic", "audit-log-topic");
        ReflectionTestUtils.setField(asyncSender, "tag", "log");

        dispatcher = new AuditLogDispatcher();
        ReflectionTestUtils.setField(dispatcher, "strategies", Arrays.asList(syncSender, asyncSender));
        ReflectionTestUtils.setField(dispatcher, "defaultDeliveryModeConfig", "ASYNC_MQ");
        dispatcher.init();
    }

    @Test
    @DisplayName("测试1：同步投递模式正常落库并正确设置 DeliveryMode 为 SYNC_DB")
    public void testSyncDbDelivery() {
        AuditLogDTO dto = new AuditLogDTO();
        dto.setTraceId("trace-sync-001");
        dto.setTitle("创建订单(同步)");
        dto.setBusinessType(BusinessType.INSERT.name());
        dto.setCostTime(15L);

        dispatcher.dispatch(dto, DeliveryMode.SYNC_DB);

        // 验证调用了 AuditLogService 保存
        ArgumentCaptor<AuditLogDTO> captor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService, times(1)).saveAuditLog(captor.capture());

        AuditLogDTO saved = captor.getValue();
        Assertions.assertEquals(DeliveryMode.SYNC_DB.name(), saved.getDeliveryMode());
        Assertions.assertEquals("trace-sync-001", saved.getTraceId());
        Assertions.assertEquals("创建订单(同步)", saved.getTitle());
    }

    @Test
    @DisplayName("测试2：RocketMQ 异步投递模式向 MQ 发送消息并设置 DeliveryMode 为 ASYNC_MQ")
    public void testRocketMqAsyncDelivery() {
        AuditLogDTO dto = new AuditLogDTO();
        dto.setTraceId("trace-async-002");
        dto.setTitle("创建订单(异步)");
        dto.setBusinessType(BusinessType.INSERT.name());
        dto.setCostTime(2L);

        dispatcher.dispatch(dto, DeliveryMode.ASYNC_MQ);

        // 验证调用了 RocketMQTemplate.asyncSend
        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        verify(rocketMQTemplate, times(1)).asyncSend(
                destinationCaptor.capture(),
                messageCaptor.capture(),
                any(SendCallback.class)
        );

        Assertions.assertEquals("audit-log-topic:log", destinationCaptor.getValue());
        Assertions.assertEquals(DeliveryMode.ASYNC_MQ.name(), dto.getDeliveryMode());

        String payload = (String) messageCaptor.getValue().getPayload();
        Assertions.assertTrue(payload.contains("trace-async-002"));
        Assertions.assertTrue(payload.contains("创建订单(异步)"));
    }

    @Test
    @DisplayName("测试3：默认模式根据全局配置分发（默认配置为 ASYNC_MQ）")
    public void testDefaultModeFallback() {
        AuditLogDTO dto = new AuditLogDTO();
        dto.setTraceId("trace-default-003");
        dto.setTitle("默认配置分发");

        dispatcher.dispatch(dto, DeliveryMode.DEFAULT);

        // 默认应当走 RocketMQ 异步投递
        verify(rocketMQTemplate, times(1)).asyncSend(
                anyString(),
                any(Message.class),
                any(SendCallback.class)
        );
        Assertions.assertEquals(DeliveryMode.ASYNC_MQ.name(), dto.getDeliveryMode());
    }

    public static class SampleOrderService {
        @api.audit.AuditLog(
                title = "模拟修改订单",
                businessType = BusinessType.UPDATE,
                deliveryMode = DeliveryMode.SYNC_DB,
                excludeParamNames = {"password"}
        )
        public String updateOrder(Long orderId, String status, String password) {
            return "ORDER_UPDATED:" + orderId;
        }

        @api.audit.AuditLog(
                title = "模拟失败操作",
                businessType = BusinessType.DELETE,
                deliveryMode = DeliveryMode.SYNC_DB
        )
        public void failOperation() {
            throw new IllegalStateException("模拟业务不可达异常");
        }
    }

    @Test
    @DisplayName("测试4：AOP 切面完整环绕拦截、提取元数据、脱敏参数、统计耗时并落库")
    public void testAuditLogAspectIntercept() {
        AuditLogAspect aspect = new AuditLogAspect();
        ReflectionTestUtils.setField(aspect, "auditLogDispatcher", dispatcher);
        ReflectionTestUtils.setField(aspect, "objectMapper", objectMapper);

        org.springframework.aop.aspectj.annotation.AspectJProxyFactory factory =
                new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(new SampleOrderService());
        factory.addAspect(aspect);
        SampleOrderService proxy = factory.getProxy();

        String result = proxy.updateOrder(888L, "PAID", "mySecretPassword123");
        Assertions.assertEquals("ORDER_UPDATED:888", result);

        ArgumentCaptor<AuditLogDTO> captor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService, atLeastOnce()).saveAuditLog(captor.capture());

        AuditLogDTO captured = captor.getValue();
        Assertions.assertEquals("模拟修改订单", captured.getTitle());
        Assertions.assertEquals(BusinessType.UPDATE.name(), captured.getBusinessType());
        Assertions.assertEquals(DeliveryMode.SYNC_DB.name(), captured.getDeliveryMode());
        Assertions.assertEquals(0, captured.getStatus());
        Assertions.assertNotNull(captured.getTraceId());
        Assertions.assertNotNull(captured.getCostTime());
        Assertions.assertTrue(captured.getResponseResult().contains("ORDER_UPDATED:888"));
        // 验证敏感密码被成功脱敏
        Assertions.assertTrue(captured.getRequestParams().contains("已脱敏"));
        Assertions.assertFalse(captured.getRequestParams().contains("mySecretPassword123"));
    }

    @Test
    @DisplayName("测试5：AOP 切面在目标方法抛出异常时，正确捕获异常堆栈且设置 status 为 1")
    public void testAuditLogAspectExceptionIntercept() {
        AuditLogAspect aspect = new AuditLogAspect();
        ReflectionTestUtils.setField(aspect, "auditLogDispatcher", dispatcher);
        ReflectionTestUtils.setField(aspect, "objectMapper", objectMapper);

        org.springframework.aop.aspectj.annotation.AspectJProxyFactory factory =
                new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(new SampleOrderService());
        factory.addAspect(aspect);
        SampleOrderService proxy = factory.getProxy();

        Assertions.assertThrows(IllegalStateException.class, proxy::failOperation);

        ArgumentCaptor<AuditLogDTO> captor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService, atLeastOnce()).saveAuditLog(captor.capture());

        AuditLogDTO captured = captor.getValue();
        Assertions.assertEquals("模拟失败操作", captured.getTitle());
        Assertions.assertEquals(1, captured.getStatus());
        Assertions.assertNotNull(captured.getErrorMsg());
        Assertions.assertTrue(captured.getErrorMsg().contains("模拟业务不可达异常"));
    }
}

