package com.example.item.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.payment.domain.ChannelSettlementOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ChannelSettlementOrderMapper extends BaseMapper<ChannelSettlementOrder> {

    @Select("SELECT * FROM t_channel_settlement_order WHERE settlement_no = #{settlementNo}")
    ChannelSettlementOrder selectBySettlementNo(@Param("settlementNo") String settlementNo);

    @Select("SELECT * FROM t_channel_settlement_order WHERE status IN ('PLANNED', 'DUE') " +
            "AND expected_settled_at <= #{now} LIMIT #{limit}")
    List<ChannelSettlementOrder> selectDueSettlements(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("SELECT * FROM t_channel_settlement_order WHERE profile_id = #{profileId} " +
            "AND business_date = #{businessDate}")
    List<ChannelSettlementOrder> selectByProfileAndDate(@Param("profileId") String profileId,
                                                        @Param("businessDate") LocalDate businessDate);
}
