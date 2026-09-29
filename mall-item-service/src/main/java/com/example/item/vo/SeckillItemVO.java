package com.example.item.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillItemVO {
    private Long id;
    private Long sessionId;
    private Long itemId;
    private String itemName;
    private String subTitle;
    private String imageUrl;
    private String detailHtml;
    private BigDecimal originalPrice;
    private BigDecimal seckillPrice;
    private Integer seckillStock;
    private Integer remainStock;
    private Integer percent;      // 已抢百分比 (0-100)
    private Boolean isSoldOut;     // 是否已售罄
    private Integer limitPerUser;  // 单人限购
}
