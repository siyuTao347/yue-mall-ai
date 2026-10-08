package com.example.item.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ItemSummaryDTO(
        Long id,
        String itemName,
        BigDecimal price,
        Integer stock,
        Integer frozenStock,
        String subTitle,
        String imageUrl,
        Integer status,
        Long merchantId,
        Long sellerId,
        String assetType,
        String deliveryMode,
        String auditStatus,
        String auditRemark,
        String riskStatus,
        String riskLevel,
        LocalDateTime updatedTime
) {
}
