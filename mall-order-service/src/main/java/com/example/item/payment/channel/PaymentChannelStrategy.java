package com.example.item.payment.channel;

import com.example.item.payment.channel.ChannelPaymentCommands.*;
import com.example.item.payment.domain.PaymentMethod;

public interface PaymentChannelStrategy {

    ChannelKey key();

    PaymentMethod paymentMethod();

    ChannelCapabilities capabilities();

    CreatePaymentResult createPayment(CreateChannelPayment command);

    ChannelPaymentResult queryPayment(QueryChannelPayment query);

    ClosePaymentResult closePayment(CloseChannelPayment command);

    ChannelRefundResult refund(RefundChannelPayment command);

    ChannelRefundResult queryRefund(QueryChannelRefund query);

    VerifiedChannelEvent verifyAndParse(RawChannelNotification notification);
}
