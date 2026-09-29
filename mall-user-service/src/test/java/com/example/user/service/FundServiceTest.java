package com.example.user.service;

import api.trade.FundOperationResult;
import com.example.user.entity.FundTransaction;
import com.example.user.entity.PlatformAccount;
import com.example.user.mapper.FundFlowMapper;
import com.example.user.mapper.FundTransactionMapper;
import com.example.user.mapper.PlatformAccountMapper;
import com.example.user.mapper.UserAccountMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundServiceTest {
    private FundTransactionMapper transactionMapper;
    private PlatformAccountMapper platformMapper;
    private FundService service;

    @BeforeEach
    void setUp() {
        transactionMapper = mock(FundTransactionMapper.class);
        platformMapper = mock(PlatformAccountMapper.class);
        service = new FundService(transactionMapper, mock(FundFlowMapper.class),
                mock(UserAccountMapper.class), platformMapper);
    }

    @Test
    void payDepositRejectsIdempotencyKeyReuseWithDifferentAmount() {
        when(platformMapper.selectOne(any())).thenReturn(new PlatformAccount());
        when(transactionMapper.selectOne(any())).thenReturn(depositTransaction(new BigDecimal("100.00")));

        FundOperationResult result = service.payDeposit("DEPOSIT1", 10L, 20L,
                new BigDecimal("200.00"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("幂等请求参数不一致", result.getMessage());
        verify(transactionMapper, never()).insert(any(FundTransaction.class));
        verify(platformMapper, never()).creditDeposit(any(BigDecimal.class));
    }

    @Test
    void payDepositReturnsExistingTransactionForSameRequest() {
        FundTransaction existing = depositTransaction(new BigDecimal("100.00"));
        when(platformMapper.selectOne(any())).thenReturn(new PlatformAccount());
        when(transactionMapper.selectOne(any())).thenReturn(existing);

        FundOperationResult result = service.payDeposit("DEPOSIT1", 10L, 20L,
                new BigDecimal("100.00"));

        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("FUND1", result.getTransactionNo());
        verify(transactionMapper, never()).insert(any(FundTransaction.class));
        verify(platformMapper, never()).creditDeposit(any(BigDecimal.class));
    }

    private FundTransaction depositTransaction(BigDecimal amount) {
        FundTransaction transaction = new FundTransaction();
        transaction.setTransactionNo("FUND1");
        transaction.setBusinessType("DEPOSIT_PAY");
        transaction.setOrderNo("DEPOSIT1");
        transaction.setMerchantId(10L);
        transaction.setUserId(20L);
        transaction.setAmount(amount);
        transaction.setStatus("SUCCESS");
        transaction.setIdempotencyKey("DEPOSIT_PAY:DEPOSIT1");
        return transaction;
    }
}
