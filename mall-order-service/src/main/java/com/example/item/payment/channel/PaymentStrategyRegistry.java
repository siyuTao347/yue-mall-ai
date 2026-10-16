package com.example.item.payment.channel;

import com.example.item.payment.domain.PaymentMethod;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public final class PaymentStrategyRegistry {

    private final Map<ChannelKey, PaymentChannelStrategy> strategies;
    private final Map<PaymentMethod, PaymentChannelStrategy> methodStrategies;

    public PaymentStrategyRegistry(List<PaymentChannelStrategy> candidateList) {
        if (candidateList == null || candidateList.isEmpty()) {
            this.strategies = Collections.emptyMap();
            this.methodStrategies = Collections.emptyMap();
            return;
        }

        Map<ChannelKey, PaymentChannelStrategy> keyMap = new HashMap<>();
        Map<PaymentMethod, PaymentChannelStrategy> methodMap = new EnumMap<>(PaymentMethod.class);

        for (PaymentChannelStrategy strategy : candidateList) {
            ChannelKey key = strategy.key();
            if (keyMap.putIfAbsent(key, strategy) != null) {
                throw new IllegalStateException("Duplicate payment channel strategy registered for key: " + key);
            }
            if (methodMap.putIfAbsent(strategy.paymentMethod(), strategy) != null) {
                // If multiple strategies for same method exist, the first one is default
            }
        }

        this.strategies = Collections.unmodifiableMap(keyMap);
        this.methodStrategies = Collections.unmodifiableMap(methodMap);
    }

    public PaymentChannelStrategy require(ChannelKey key) {
        PaymentChannelStrategy strategy = strategies.get(key);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported payment channel key: " + key);
        }
        return strategy;
    }

    public PaymentChannelStrategy require(PaymentMethod method) {
        PaymentChannelStrategy strategy = methodStrategies.get(method);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported payment method: " + method);
        }
        return strategy;
    }

    public boolean supports(PaymentMethod method) {
        return methodStrategies.containsKey(method);
    }

    public Collection<PaymentChannelStrategy> allStrategies() {
        return strategies.values();
    }
}
