package com.example.item.payment.mock;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_mock_bank_entry")
public class MockBankEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bankEntryNo;
    private String externalReference;
    private Integer entrySeq;
    private String accountId;
    private BigDecimal amount;
    private String direction;
    private LocalDate valueDate;
    private LocalDateTime entryTime;
    private LocalDateTime createdTime;
}
