package com.example.item.payment.mock;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MockChannelRefundMapper extends BaseMapper<MockChannelRefund> {

    @Select("SELECT * FROM t_mock_channel_refund WHERE refund_no = #{refundNo}")
    MockChannelRefund selectByRefundNo(@Param("refundNo") String refundNo);

    @Select("SELECT * FROM t_mock_channel_refund WHERE channel_merchant_no = #{merchantNo} " +
            "AND channel_business_date = #{businessDate} ORDER BY id ASC")
    List<MockChannelRefund> listByBusinessDate(@Param("merchantNo") String merchantNo,
                                               @Param("businessDate") LocalDate businessDate);
}
