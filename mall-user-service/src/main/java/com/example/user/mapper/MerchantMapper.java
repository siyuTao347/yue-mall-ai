package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.user.entity.Merchant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface MerchantMapper extends BaseMapper<Merchant> {
    @Update("UPDATE t_merchant SET status = #{targetStatus}, reject_reason = #{rejectReason}, " +
            "updated_time = #{now} WHERE id = #{id} AND status = #{currentStatus}")
    int updateStatus(@Param("id") Long id, @Param("currentStatus") String currentStatus,
                     @Param("targetStatus") String targetStatus, @Param("rejectReason") String rejectReason,
                     @Param("now") LocalDateTime now);
}
