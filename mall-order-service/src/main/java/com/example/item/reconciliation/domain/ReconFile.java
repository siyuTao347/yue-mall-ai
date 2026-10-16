package com.example.item.reconciliation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_recon_file")
public class ReconFile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileNo;
    private String profileId;
    private String channelMerchantNo;
    private String statementType;
    private LocalDate businessDate;
    private Integer version;
    private String checksum;
    private String parserVersion;
    private String fileLocation;
    private String rawContent;
    private Integer rowCount;
    private String status;
    private LocalDateTime createdTime;
}
