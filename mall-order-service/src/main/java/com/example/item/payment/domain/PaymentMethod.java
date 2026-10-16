package com.example.item.payment.domain;

public enum PaymentMethod {
    UNIONPAY_BANK("银行卡/银联", "UNIONPAY", "B2C_EXPRESS"),
    WECHAT_PAY("微信支付", "WECHAT", "NATIVE_APP"),
    ALIPAY("支付宝", "ALIPAY", "DIRECT_PAY"),
    ECNY("数字人民币", "PBOC_ECNY", "WALLET_PAY");

    private final String displayName;
    private final String defaultProvider;
    private final String defaultProduct;

    PaymentMethod(String displayName, String defaultProvider, String defaultProduct) {
        this.displayName = displayName;
        this.defaultProvider = defaultProvider;
        this.defaultProduct = defaultProduct;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public String getDefaultProduct() {
        return defaultProduct;
    }
}
