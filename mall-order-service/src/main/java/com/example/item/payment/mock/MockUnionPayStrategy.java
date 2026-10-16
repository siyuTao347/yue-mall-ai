package com.example.item.payment.mock;

import com.example.item.payment.channel.ChannelCapabilities;
import com.example.item.payment.channel.ChannelKey;
import com.example.item.payment.channel.ChannelPaymentCommands.*;
import com.example.item.payment.channel.PaymentChannelStrategy;
import com.example.item.payment.domain.PaymentMethod;
import org.springframework.stereotype.Component;

@Component
public class MockUnionPayStrategy implements PaymentChannelStrategy {

    private final MockChannelEngine engine;

    public MockUnionPayStrategy(MockChannelEngine engine) {
        this.engine = engine;
    }

    @Override
    public ChannelKey key() {
        return ChannelKey.ofMock("UNIONPAY", "B2C_EXPRESS");
    }

    @Override
    public PaymentMethod paymentMethod() {
        return PaymentMethod.UNIONPAY_BANK;
    }

    @Override
    public ChannelCapabilities capabilities() {
        return ChannelCapabilities.standard();
    }

    @Override
    public CreatePaymentResult createPayment(CreateChannelPayment command) {
        CashierAction action = new CashierAction(
                "CARD_FORM",
                "MOCK_CARD_622202******8888",
                "/mock/cashier/unionpay?attemptNo=" + command.attemptNo(),
                "银联全渠道模拟支付通道（23:00 清算日切，T+1 工作日结算到账）"
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
                : com.example.item.payment.channel.ChannelProfileSnapshot.defaultFor(PaymentMethod.UNIONPAY_BANK));
    }
}
