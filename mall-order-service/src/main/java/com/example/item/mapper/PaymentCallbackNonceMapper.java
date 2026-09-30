package com.example.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.entity.PaymentCallbackNonce;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface PaymentCallbackNonceMapper extends BaseMapper<PaymentCallbackNonce> {

    @Select("SELECT COUNT(*) FROM t_payment_callback_nonce WHERE nonce = #{nonce}")
    long countByNonce(@Param("nonce") String nonce);

    @Delete("DELETE FROM t_payment_callback_nonce WHERE expire_time < #{cutoffTime}")
    int deleteExpiredBefore(@Param("cutoffTime") LocalDateTime cutoffTime);
}
