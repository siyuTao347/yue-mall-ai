package com.example.item.payment.mock;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MockSettlementTransferMapper extends BaseMapper<MockSettlementTransfer> {

    @Select("SELECT * FROM t_mock_settlement_transfer WHERE profile_id = #{profileId} " +
            "AND business_date = #{businessDate} ORDER BY id ASC")
    List<MockSettlementTransfer> listByDate(@Param("profileId") String profileId,
                                            @Param("businessDate") LocalDate businessDate);
}
