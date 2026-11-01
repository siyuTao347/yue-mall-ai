package com.example.moneyagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.moneyagent.persistence.entity.MoneyCaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MoneyCaseMapper extends BaseMapper<MoneyCaseEntity> {

    @Select("SELECT * FROM t_money_case WHERE case_no = #{caseNo} FOR UPDATE")
    MoneyCaseEntity selectForUpdate(@Param("caseNo") String caseNo);

    @Select("SELECT * FROM t_money_case WHERE active_dedupe_key = #{dedupeKey} AND state IN ('OPEN', 'NEED_INPUT', 'NEED_REVIEW', 'WAITING_BIZ') LIMIT 1 FOR UPDATE")
    MoneyCaseEntity selectActiveByDedupeKeyForUpdate(@Param("dedupeKey") String dedupeKey);
}

