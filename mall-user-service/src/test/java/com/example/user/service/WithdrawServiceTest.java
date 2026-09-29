package com.example.user.service;

import api.trade.FundOperationResult;
import com.example.user.entity.Merchant;
import com.example.user.entity.WithdrawRequest;
import com.example.user.mapper.WithdrawRequestMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WithdrawServiceTest {
    private WithdrawRequestMapper withdrawMapper;
    private MerchantService merchantService;
    private FundService fundService;
    private WithdrawService service;

    @BeforeEach
    void setUp() {
        withdrawMapper = mock(WithdrawRequestMapper.class);
        merchantService = mock(MerchantService.class);
        fundService = mock(FundService.class);
        service = new WithdrawService(withdrawMapper, merchantService, fundService);
    }

    @Test
    void applyUsesStableWithdrawNoForIdempotency() {
        when(withdrawMapper.selectOne(any())).thenReturn(null);
        when(fundService.withdrawFreeze("W20-20260928token", 20L, 10L,
                new BigDecimal("100.00"))).thenReturn(FundOperationResult.success("FUND1"));

        WithdrawRequest result = service.apply(10L, 20L, new BigDecimal("100.00"),
                "MOCK-ACCOUNT", "20260928token");

        Assertions.assertEquals("W20-20260928token", result.getWithdrawNo());
        ArgumentCaptor<WithdrawRequest> captor = ArgumentCaptor.forClass(WithdrawRequest.class);
        verify(withdrawMapper).insert(captor.capture());
        Assertions.assertEquals("W20-20260928token", captor.getValue().getWithdrawNo());
    }

    @Test
    void applyReturnsExistingRequestForSameClientToken() {
        WithdrawRequest existing = request();
        when(withdrawMapper.selectOne(any())).thenReturn(existing);

        WithdrawRequest result = service.apply(10L, 20L, new BigDecimal("100.00"),
                "MOCK-ACCOUNT", "20260928token");

        Assertions.assertSame(existing, result);
        verify(fundService, never()).withdrawFreeze(anyString(), any(), any(), any());
        verify(withdrawMapper, never()).insert(any(WithdrawRequest.class));
    }

    @Test
    void applyRejectsInvalidClientTokenBeforeChangingFunds() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.apply(10L, 20L, new BigDecimal("100.00"), "MOCK-ACCOUNT", "bad"));

        verify(fundService, never()).withdrawFreeze(anyString(), any(), any(), any());
        verify(withdrawMapper, never()).insert(any(WithdrawRequest.class));
    }

    private WithdrawRequest request() {
        WithdrawRequest request = new WithdrawRequest();
        request.setWithdrawNo("W20-20260928token");
        request.setMerchantId(10L);
        request.setUserId(20L);
        request.setAmount(new BigDecimal("100.00"));
        request.setMockAccount("MOCK-ACCOUNT");
        request.setStatus("SUBMITTED");
        return request;
    }
}
