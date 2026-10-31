package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.user.entity.FundBusinessRef;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FundBusinessRefMapper extends BaseMapper<FundBusinessRef> {

    @Select("<script>" +
            "SELECT * FROM t_fund_business_ref WHERE ref_type = #{refType} AND ref_no IN " +
            "<foreach item='item' collection='refNos' open='(' separator=',' close=')'>" +
            "#{item}" +
            "</foreach>" +
            "</script>")
    List<FundBusinessRef> selectByRefTypeAndNos(@Param("refType") String refType, @Param("refNos") List<String> refNos);

    @Select("<script>" +
            "SELECT * FROM t_fund_business_ref WHERE transaction_no IN " +
            "<foreach item='item' collection='txNos' open='(' separator=',' close=')'>" +
            "#{item}" +
            "</foreach>" +
            "</script>")
    List<FundBusinessRef> selectByTransactionNos(@Param("txNos") List<String> txNos);
}

