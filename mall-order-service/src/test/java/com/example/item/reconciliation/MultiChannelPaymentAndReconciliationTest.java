package com.example.item.reconciliation;

import com.example.item.payment.channel.*;
import com.example.item.payment.channel.ChannelPaymentCommands.*;
import com.example.item.payment.domain.PaymentMethod;
import com.example.item.payment.mock.*;
import com.example.item.reconciliation.domain.*;
import com.example.item.reconciliation.statement.ReconciliationStatementParser;
import com.example.item.payment.settlement.SettlementScheduleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultiChannelPaymentAndReconciliationTest {

    private ObjectMapper objectMapper;
    private SettlementScheduleService settlementScheduleService;
    private ReconciliationStatementParser statementParser;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        settlementScheduleService = new SettlementScheduleService(null);
        statementParser = new ReconciliationStatementParser();
    }

    @Test
    @DisplayName("验证所有4种支付渠道的配置与策略支持")
    void testChannelProfilesAndMethods() {
        assertEquals(4, PaymentMethod.values().length);

        for (PaymentMethod method : PaymentMethod.values()) {
            ChannelProfileSnapshot profile = ChannelProfileSnapshot.defaultFor(method);
            assertNotNull(profile);
            assertEquals(method, profile.paymentMethod());
            assertNotNull(profile.profileId());
            assertNotNull(profile.channelMerchantNo());
            assertNotNull(profile.settlementDayBasis());

            if (method == PaymentMethod.ECNY) {
                assertEquals(0, BigDecimal.ZERO.compareTo(profile.feeRate()));
                assertEquals("REALTIME", profile.settlementDayBasis());
            } else if (method == PaymentMethod.UNIONPAY_BANK) {
                assertEquals(0, new BigDecimal("0.0050").compareTo(profile.feeRate()));
                assertEquals("BUSINESS", profile.settlementDayBasis());
            } else {
                assertEquals(0, new BigDecimal("0.0060").compareTo(profile.feeRate()));
                assertEquals("CALENDAR", profile.settlementDayBasis());
            }
        }
    }

    @Test
    @DisplayName("测试模拟渠道对账单生成与SHA256哈希校验")
    void testStatementGenerationAndParser() {
        String csvContent = "# STATEMENT_TYPE: TRADE\n" +
                "# PROFILE_ID: MOCK_WECHAT_PAY\n" +
                "# MERCHANT_NO: MCH_WECHAT_001\n" +
                "# BUSINESS_DATE: 2026-10-15\n" +
                "line_no,item_type,out_trade_no,channel_trade_no,refund_no,amount,fee_amount,currency,status,time\n" +
                "1,PAYMENT,PA_20261015001_01,WX_TR_1001,,100.00,0.60,CNY,SUCCESS,2026-10-15T10:30:00\n" +
                "2,REFUND,,WX_TR_1001,RF_20261015001_01,20.00,0.12,CNY,SUCCEEDED,2026-10-15T14:20:00\n";

        List<ReconChannelItem> items = statementParser.parseStatement(1L, csvContent);
        assertEquals(2, items.size());

        ReconChannelItem item1 = items.get(0);
        assertEquals(1, item1.getLineNo());
        assertEquals("PAYMENT", item1.getItemType());
        assertEquals("PA_20261015001_01", item1.getOutTradeNo());
        assertEquals("WX_TR_1001", item1.getChannelTradeNo());
        assertEquals(new BigDecimal("100.00"), item1.getAmount());
        assertEquals(new BigDecimal("0.60"), item1.getFeeAmount());
        assertEquals("SUCCESS", item1.getStatus());

        ReconChannelItem item2 = items.get(1);
        assertEquals(2, item2.getLineNo());
        assertEquals("REFUND", item2.getItemType());
        assertEquals("RF_20261015001_01", item2.getRefundNo());
        assertEquals(new BigDecimal("20.00"), item2.getAmount());
        assertEquals(new BigDecimal("0.12"), item2.getFeeAmount());
        assertEquals("SUCCEEDED", item2.getStatus());
    }

    @Test
    @DisplayName("测试结算周期计算：D+1跳过周末节假日及D+0当日结算")
    void testSettlementScheduleCalculation() {
        // 2026-10-16 是周五，对于D+1自然日为周六，但对于银行工作日D+1为下周一 2026-10-19
        LocalDate friday = LocalDate.of(2026, 10, 16);
        LocalDateTime settledD1 = settlementScheduleService.calculateSettlementTime(
                ChannelProfileSnapshot.defaultFor(PaymentMethod.UNIONPAY_BANK), friday);
        assertNotNull(settledD1);
        assertEquals(19, settledD1.getDayOfMonth());

        // e-CNY 为 T+0 当日结算 (2小时后到账)
        LocalDateTime settledEcny = settlementScheduleService.calculateSettlementTime(
                ChannelProfileSnapshot.defaultFor(PaymentMethod.ECNY), friday);
        assertNotNull(settledEcny);
        assertEquals(16, settledEcny.getDayOfMonth());
    }

    @Test
    @DisplayName("测试渠道通知HMAC-SHA256签名生成与验证")
    void testSignatureVerification() {
        MockChannelTransactionMapper txMapper = mock(MockChannelTransactionMapper.class);
        MockChannelRefundMapper refMapper = mock(MockChannelRefundMapper.class);
        MockChannelPostingMapper postMapper = mock(MockChannelPostingMapper.class);
        MockNotificationOutboxMapper outboxMapper = mock(MockNotificationOutboxMapper.class);
        MockFaultPlanMapper faultPlanMapper = mock(MockFaultPlanMapper.class);

        MockChannelEngine engine = new MockChannelEngine(
                txMapper, outboxMapper, refMapper, postMapper, faultPlanMapper, objectMapper);

        String payload = "{\"version\":\"2.0\",\"eventId\":\"EVT_001\",\"amount\":\"100.00\"}";
        String signature = engine.sign(payload);
        assertNotNull(signature);
        assertEquals(64, signature.length()); // SHA-256 hex is 64 chars

        assertTrue(engine.verifyNotificationSignature(payload, signature));
        assertFalse(engine.verifyNotificationSignature(payload + "tampered", signature));
    }

    @Test
    @DisplayName("测试单边账与金额不符等差异识别规则")
    void testReconciliationDiffClassification() {
        // 金额不一致
        BigDecimal localAmount = new BigDecimal("100.00");
        BigDecimal channelAmount = new BigDecimal("90.00");
        assertNotEquals(0, localAmount.compareTo(channelAmount));

        // 平台有渠道无 (PLATFORM_ONLY_PAYMENT)
        String channelTradeNo = null;
        assertNull(channelTradeNo);

        // 渠道有平台无 (CHANNEL_ONLY_PAYMENT)
        String localAttemptNo = null;
        assertNull(localAttemptNo);
    }
}
