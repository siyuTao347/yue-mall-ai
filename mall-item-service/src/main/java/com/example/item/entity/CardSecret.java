package com.example.item.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_card_secret")
public class CardSecret {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long itemId;
    private Long merchantId;
    private byte[] secretCipher;
    private String secretHash;
    private String secretMask;
    private String status;
    private String orderNo;
    private String reservationNo;
    private LocalDateTime lockedTime;
    private LocalDateTime soldTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
