package com.example.item.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.payment.domain.PaymentFinancialEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PaymentFinancialEventMapper extends BaseMapper<PaymentFinancialEvent> {

    @Select("SELECT * FROM t_payment_financial_event WHERE profile_id = #{profileId} " +
            "AND channel_merchant_no = #{merchantNo} AND channel_business_date = #{businessDate} " +
            "AND recorded_at < #{snapshotCutoff} ORDER BY id ASC")
    List<PaymentFinancialEvent> selectForReconciliation(@Param("profileId") String profileId,
                                                        @Param("merchantNo") String merchantNo,
                                                        @Param("businessDate") LocalDate businessDate,
                                                        @Param("snapshotCutoff") LocalDateTime snapshotCutoff);

    @Select("SELECT * FROM t_payment_financial_event WHERE profile_id = #{profileId} " +
            "AND channel_merchant_no = #{merchantNo} AND channel_business_date = #{businessDate}")
    List<PaymentFinancialEvent> selectByBusinessDate(@Param("profileId") String profileId,
                                                     @Param("merchantNo") String merchantNo,
                                                     @Param("businessDate") LocalDate businessDate);
}
