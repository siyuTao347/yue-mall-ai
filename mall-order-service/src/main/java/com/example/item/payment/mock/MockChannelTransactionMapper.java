package com.example.item.payment.mock;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MockChannelTransactionMapper extends BaseMapper<MockChannelTransaction> {

    @Select("SELECT * FROM t_mock_channel_transaction WHERE out_trade_no = #{outTradeNo}")
    MockChannelTransaction selectByOutTradeNo(@Param("outTradeNo") String outTradeNo);

    @Select("SELECT * FROM t_mock_channel_transaction WHERE channel_trade_no = #{channelTradeNo}")
    MockChannelTransaction selectByChannelTradeNo(@Param("channelTradeNo") String channelTradeNo);

    @Select("SELECT * FROM t_mock_channel_transaction WHERE profile_id = #{profileId} " +
            "AND channel_merchant_no = #{merchantNo} AND channel_business_date = #{businessDate} ORDER BY id ASC")
    List<MockChannelTransaction> listByBusinessDate(@Param("profileId") String profileId,
                                                    @Param("merchantNo") String merchantNo,
                                                    @Param("businessDate") LocalDate businessDate);
}
