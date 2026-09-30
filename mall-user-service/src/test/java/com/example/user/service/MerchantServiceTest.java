package com.example.user.service;

import com.example.user.entity.MerchantCreditOperation;
import com.example.user.mapper.MerchantAuditMapper;
import com.example.user.mapper.MerchantCreditMapper;
import com.example.user.mapper.MerchantDepositMapper;
import com.example.user.mapper.MerchantMapper;
import com.example.user.mapper.UserMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantServiceTest {
    private MerchantCreditMapper creditMapper;
    private MerchantService service;

    @BeforeEach
    void setUp() {
        creditMapper = mock(MerchantCreditMapper.class);
        service = new MerchantService(mock(MerchantMapper.class), mock(MerchantAuditMapper.class),
                mock(MerchantDepositMapper.class), creditMapper, mock(UserMapper.class),
                mock(FundService.class), mock(RiskClient.class));
    }

    @Test
    void completeOrderAppliesCreditOncePerIdempotencyKey() {
        when(creditMapper.insertOperation(any(MerchantCreditOperation.class))).thenReturn(1);
        when(creditMapper.completeOrder(eq(30L), any(BigDecimal.class))).thenReturn(1);

        service.completeOrder(30L, BigDecimal.valueOf(5), "TR1", "MERCHANT_COMPLETE:TR1");

        verify(creditMapper).ensure(30L);
        verify(creditMapper).completeOrder(eq(30L), any(BigDecimal.class));
    }

    @Test
    void completeOrderSkipsUpdateWhenOperationAlreadyExists() {
        when(creditMapper.insertOperation(any(MerchantCreditOperation.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        service.completeOrder(30L, BigDecimal.valueOf(5), "TR1", "MERCHANT_COMPLETE:TR1");

        verify(creditMapper, never()).completeOrder(eq(30L), any(BigDecimal.class));
    }

    @Test
    void refundOrderAppliesCreditOncePerIdempotencyKey() {
        when(creditMapper.insertOperation(any(MerchantCreditOperation.class))).thenReturn(1);
        when(creditMapper.refundOrder(30L)).thenReturn(1);

        service.refundOrder(30L, "TR1", "MERCHANT_REFUND:TR1");

        verify(creditMapper).refundOrder(30L);
    }

    @Test
    void refundOrderSkipsUpdateWhenOperationAlreadyExists() {
        when(creditMapper.insertOperation(any(MerchantCreditOperation.class))).thenReturn(0);

        service.refundOrder(30L, "TR1", "MERCHANT_REFUND:TR1");

        verify(creditMapper, never()).refundOrder(30L);
    }

    @Test
    void disputeOrderAppliesCreditOncePerIdempotencyKey() {
        when(creditMapper.insertOperation(any(MerchantCreditOperation.class))).thenReturn(1);
        when(creditMapper.disputeOrder(30L)).thenReturn(1);

        service.disputeOrder(30L, "TR1", "MERCHANT_DISPUTE:TR1");

        verify(creditMapper).disputeOrder(30L);
    }

    @Test
    void disputeOrderSkipsUpdateWhenOperationAlreadyExists() {
        when(creditMapper.insertOperation(any(MerchantCreditOperation.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        service.disputeOrder(30L, "TR1", "MERCHANT_DISPUTE:TR1");

        verify(creditMapper, never()).disputeOrder(30L);
    }

    @Test
    void completeOrderRejectsMismatchedIdempotencyKey() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.completeOrder(30L, BigDecimal.valueOf(5), "TR1", "MERCHANT_COMPLETE:OTHER"));

        verify(creditMapper, never()).completeOrder(eq(30L), any(BigDecimal.class));
    }

    @Test
    void completeOrderRejectsScoreOutOfRange() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.completeOrder(30L, BigDecimal.valueOf(6), "TR1", "MERCHANT_COMPLETE:TR1"));

        verify(creditMapper, never()).completeOrder(eq(30L), any(BigDecimal.class));
    }

    @Test
    void completeOrderRejectsBlankOrderNo() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.completeOrder(30L, BigDecimal.valueOf(5), " ", "MERCHANT_COMPLETE: "));

        verify(creditMapper, never()).completeOrder(eq(30L), any(BigDecimal.class));
    }
}
