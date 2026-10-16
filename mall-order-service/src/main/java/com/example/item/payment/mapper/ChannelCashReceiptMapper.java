package com.example.item.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.payment.domain.ChannelCashReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChannelCashReceiptMapper extends BaseMapper<ChannelCashReceipt> {

    @Select("SELECT * FROM t_channel_cash_receipt WHERE settlement_no = #{settlementNo}")
    List<ChannelCashReceipt> listBySettlementNo(@Param("settlementNo") String settlementNo);
}
