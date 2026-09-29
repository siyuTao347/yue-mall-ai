package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.user.entity.MerchantDeposit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface MerchantDepositMapper extends BaseMapper<MerchantDeposit> {
    @Update("UPDATE t_merchant_deposit SET total_amount = total_amount + #{amount}, " +
            "updated_time = NOW(3) WHERE merchant_id = #{merchantId} " +
            "AND total_amount = #{expectedAmount}")
    int increaseTotal(@Param("merchantId") Long merchantId,
                      @Param("expectedAmount") BigDecimal expectedAmount,
                      @Param("amount") BigDecimal amount);
}
