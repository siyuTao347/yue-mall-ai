package com.example.item.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.payment.domain.ChannelSettlementLine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChannelSettlementLineMapper extends BaseMapper<ChannelSettlementLine> {

    @Select("SELECT * FROM t_channel_settlement_line WHERE settlement_no = #{settlementNo}")
    List<ChannelSettlementLine> listBySettlementNo(@Param("settlementNo") String settlementNo);
}
