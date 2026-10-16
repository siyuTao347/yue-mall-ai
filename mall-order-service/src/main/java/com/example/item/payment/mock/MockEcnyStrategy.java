package com.example.item.payment.mock;

import com.example.item.payment.channel.ChannelCapabilities;
import com.example.item.payment.channel.ChannelKey;
import com.example.item.payment.channel.ChannelPaymentCommands.*;
import com.example.item.payment.channel.PaymentChannelStrategy;
import com.example.item.payment.domain.PaymentMethod;
import org.springframework.stereotype.Component;

@Component
public class MockEcnyStrategy implements PaymentChannelStrategy {

    private final MockChannelEngine engine;

    public MockEcnyStrategy(MockChannelEngine engine) {
        this.engine = engine;
    }

    @Override
    public ChannelKey key() {
        return ChannelKey.ofMock("PBOC_ECNY", "WALLET_PAY");
    }

    @Override
    public PaymentMethod paymentMethod() {
        return PaymentMethod.ECNY;
    }

    @Override
    public ChannelCapabilities capabilities() {
        return ChannelCapabilities.ecny();
    }

    @Override
    public CreatePaymentResult createPayment(CreateChannelPayment command) {
        CashierAction action = new CashierAction(
                "MOCK_WALLET",
                "ECNY_WALLET_DEMO_01",
                "/mock/cashier/ecny?attemptNo=" + command.attemptNo(),
                "数字人民币在线商户钱包模拟（D+0 实时入商户数币钱包，0费率，日切后全日复核）"
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
                : com.example.item.payment.channel.ChannelProfileSnapshot.defaultFor(PaymentMethod.ECNY));
    }
}
