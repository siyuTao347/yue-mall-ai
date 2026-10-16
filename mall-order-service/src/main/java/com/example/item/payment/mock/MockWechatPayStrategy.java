package com.example.item.payment.mock;

import com.example.item.payment.channel.ChannelCapabilities;
import com.example.item.payment.channel.ChannelKey;
import com.example.item.payment.channel.ChannelPaymentCommands.*;
import com.example.item.payment.channel.PaymentChannelStrategy;
import com.example.item.payment.domain.PaymentMethod;
import org.springframework.stereotype.Component;

@Component
public class MockWechatPayStrategy implements PaymentChannelStrategy {

    private final MockChannelEngine engine;

    public MockWechatPayStrategy(MockChannelEngine engine) {
        this.engine = engine;
    }

    @Override
    public ChannelKey key() {
        return ChannelKey.ofMock("WECHAT", "NATIVE_APP");
    }

    @Override
    public PaymentMethod paymentMethod() {
        return PaymentMethod.WECHAT_PAY;
    }

    @Override
    public ChannelCapabilities capabilities() {
        return ChannelCapabilities.standard();
    }

    @Override
    public CreatePaymentResult createPayment(CreateChannelPayment command) {
        CashierAction action = new CashierAction(
                "QR_CODE",
                "weixin://wxpay/bizpayurl?pr=mock_" + command.attemptNo(),
                "/mock/cashier/wechat?attemptNo=" + command.attemptNo(),
                "微信原生Native扫码支付模拟（次日 10:00 出账，D+1 11:00 到账）"
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
                : com.example.item.payment.channel.ChannelProfileSnapshot.defaultFor(PaymentMethod.WECHAT_PAY));
    }
}
