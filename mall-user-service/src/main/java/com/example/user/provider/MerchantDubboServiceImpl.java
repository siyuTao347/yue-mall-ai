package com.example.user.provider;

import api.trade.MerchantDTO;
import api.trade.MerchantDubboService;
import com.example.user.entity.Merchant;
import com.example.user.entity.MerchantDeposit;
import com.example.user.service.MerchantService;
import org.apache.dubbo.config.annotation.DubboService;

import java.math.BigDecimal;

@DubboService
public class MerchantDubboServiceImpl implements MerchantDubboService {
    private final MerchantService merchantService;

    public MerchantDubboServiceImpl(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @Override
    public MerchantDTO getApprovedMerchantByUserId(Long userId) {
        Merchant merchant = merchantService.getByUserId(userId);
        return merchant == null ? null : toDto(merchant);
    }

    @Override
    public boolean isAdmin(Long userId) {
        return merchantService.isAdmin(userId);
    }

    private MerchantDTO toDto(Merchant merchant) {
        if (merchant == null || !"APPROVED".equals(merchant.getStatus())
                || "MANUAL_REVIEW".equals(merchant.getRiskStatus())
                || "FROZEN".equals(merchant.getRiskStatus())
                || "REJECTED".equals(merchant.getRiskStatus())) {
            return null;
        }
        MerchantDeposit deposit = merchantService.getDeposit(merchant.getId(), merchant.getUserId());
        BigDecimal total = deposit == null ? BigDecimal.ZERO : deposit.getTotalAmount();
        BigDecimal frozen = deposit == null ? BigDecimal.ZERO : deposit.getFrozenAmount();
        BigDecimal deducted = deposit == null ? BigDecimal.ZERO : deposit.getDeductedAmount();
        return MerchantDTO.builder()
                .id(merchant.getId())
                .userId(merchant.getUserId())
                .merchantName(merchant.getMerchantName())
                .status(merchant.getStatus())
                .totalDeposit(total)
                .frozenDeposit(frozen)
                .deductedDeposit(deducted)
                .createdTime(merchant.getCreatedTime())
                .build();
    }

    @Override
    public void completeOrder(Long merchantId, BigDecimal score) {
        merchantService.completeOrder(merchantId, score);
    }

    @Override
    public void refundOrder(Long merchantId) {
        merchantService.refundOrder(merchantId);
    }

    @Override
    public void disputeOrder(Long merchantId) {
        merchantService.disputeOrder(merchantId);
    }
}
