package com.example.item.payment.mock;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface MockStatementMapper extends BaseMapper<MockStatement> {

    @Select("SELECT * FROM t_mock_statement WHERE profile_id = #{profileId} " +
            "AND statement_type = #{type} AND business_date = #{date} ORDER BY version DESC LIMIT 1")
    MockStatement findLatest(@Param("profileId") String profileId,
                             @Param("type") String type,
                             @Param("date") LocalDate date);
}
