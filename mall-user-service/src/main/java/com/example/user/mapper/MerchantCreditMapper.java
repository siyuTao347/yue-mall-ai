package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.user.entity.MerchantCreditOperation;
import com.example.user.entity.MerchantCredit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface MerchantCreditMapper extends BaseMapper<MerchantCredit> {
    @Insert("INSERT INTO t_merchant_credit_operation " +
            "(operation_key, merchant_id, operation_type, score, created_time) " +
            "VALUES (#{operationKey}, #{merchantId}, #{operationType}, #{score}, #{createdTime})")
    int insertOperation(MerchantCreditOperation operation);

    @Insert("INSERT INTO t_merchant_credit (merchant_id, total_order_count, completed_order_count, " +
            "refund_order_count, dispute_order_count, avg_score, credit_score) " +
            "VALUES (#{merchantId}, 0, 0, 0, 0, 5.00, 80) " +
            "ON DUPLICATE KEY UPDATE merchant_id = merchant_id")
    int ensure(@Param("merchantId") Long merchantId);

    @Update("UPDATE t_merchant_credit SET total_order_count = total_order_count + 1, " +
            "completed_order_count = completed_order_count + 1, " +
            "avg_score = ROUND((avg_score * completed_order_count + #{score}) / (completed_order_count + 1), 2), " +
            "credit_score = GREATEST(0, LEAST(100, credit_score + 1)), updated_time = NOW(3) " +
            "WHERE merchant_id = #{merchantId}")
    int completeOrder(@Param("merchantId") Long merchantId, @Param("score") BigDecimal score);

    @Update("UPDATE t_merchant_credit SET total_order_count = total_order_count + 1, " +
            "refund_order_count = refund_order_count + 1, " +
            "credit_score = GREATEST(0, credit_score - 3), updated_time = NOW(3) " +
            "WHERE merchant_id = #{merchantId}")
    int refundOrder(@Param("merchantId") Long merchantId);

    @Update("UPDATE t_merchant_credit SET total_order_count = total_order_count + 1, " +
            "dispute_order_count = dispute_order_count + 1, " +
            "credit_score = GREATEST(0, credit_score - 2), updated_time = NOW(3) " +
            "WHERE merchant_id = #{merchantId}")
    int disputeOrder(@Param("merchantId") Long merchantId);
}
