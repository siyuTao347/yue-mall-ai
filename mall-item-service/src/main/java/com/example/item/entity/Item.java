package com.example.item.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_item")
public class Item {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String itemName;
    private BigDecimal price;
    private Integer stock;
    private Integer frozenStock;
    private Integer version;
    private Long categoryId;
    private String subTitle;
    private String imageUrl;
    private String detailHtml;
    private Integer status;
    private Long merchantId;
    private Long sellerId;
    private String assetType;
    private String deliveryMode;
    private String sourceDescription;
    private String riskNotice;
    private String auditStatus;
    private String auditRemark;
    private String riskStatus;
    private String riskLevel;
    private String riskDecisionNo;
    private String riskReason;
    private LocalDateTime updatedTime;
}
