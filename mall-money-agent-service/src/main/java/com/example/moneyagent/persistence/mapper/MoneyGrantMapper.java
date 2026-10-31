package com.example.moneyagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.moneyagent.persistence.entity.MoneyGrantEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MoneyGrantMapper extends BaseMapper<MoneyGrantEntity> {
}

