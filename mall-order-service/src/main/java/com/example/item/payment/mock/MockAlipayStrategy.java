package com.example.item.payment.mock;

import com.example.item.payment.channel.ChannelCapabilities;
import com.example.item.payment.channel.ChannelKey;
import com.example.item.payment.channel.ChannelPaymentCommands.*;
import com.example.item.payment.channel.PaymentChannelStrategy;
import com.example.item.payment.domain.PaymentMethod;
import org.springframework.stereotype.Component;

@Component
public class MockAlipayStrategy implements PaymentChannelStrategy {

    private final MockChannelEngine engine;

    public MockAlipayStrategy(MockChannelEngine engine) {
        this.engine = engine;
    }

    @Override
    public ChannelKey key() {
        return ChannelKey.ofMock("ALIPAY", "DIRECT_PAY");
    }

    @Override
    public PaymentMethod paymentMethod() {
        return PaymentMethod.ALIPAY;
    }

    @Override
    public ChannelCapabilities capabilities() {
        return ChannelCapabilities.standard();
    }

    @Override
    public CreatePaymentResult createPayment(CreateChannelPayment command) {
        CashierAction action = new CashierAction(
                "QR_CODE",
                "https://qr.alipay.com/bax_mock_" + command.attemptNo(),
                "/mock/cashier/alipay?attemptNo=" + command.attemptNo(),
                "支付宝直连收银台模拟（次日 08:00 出账，D+1 09:00 到账）"
        );
        return engine.processCreatePayment(command, action);
    }

    @Override
    public ChannelPaymentResult queryPayment(QueryChannelPayment query) {
        return engine.queryPayment(query);
    }

    @Override
    public ClosePaymentResult closePayment(CloseChannelPayment command) {
        return engine.closePayment(command);
    }

    @Override
    public ChannelRefundResult refund(RefundChannelPayment command) {
        return engine.processRefund(command);
    }

    @Override
    public ChannelRefundResult queryRefund(QueryChannelRefund query) {
        return engine.queryRefund(query);
    }

    @Override
    public VerifiedChannelEvent verifyAndParse(RawChannelNotification notification) {
        return engine.verifyAndParse(notification, notification.paymentMethod() != null
                ? com.example.item.payment.channel.ChannelProfileSnapshot.defaultFor(notification.paymentMethod())
                : com.example.item.payment.channel.ChannelProfileSnapshot.defaultFor(PaymentMethod.ALIPAY));
    }
}
