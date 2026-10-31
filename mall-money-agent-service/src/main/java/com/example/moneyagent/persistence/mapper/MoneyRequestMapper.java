package com.example.moneyagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.moneyagent.persistence.entity.MoneyRequestEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MoneyRequestMapper extends BaseMapper<MoneyRequestEntity> {
}

