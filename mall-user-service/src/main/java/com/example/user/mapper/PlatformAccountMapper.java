package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.user.entity.PlatformAccount;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformAccountMapper extends BaseMapper<PlatformAccount> {
    int creditEscrow(java.math.BigDecimal amount);

    int debitEscrow(java.math.BigDecimal amount);

    int creditRevenue(java.math.BigDecimal amount);

    int creditWithdrawPending(java.math.BigDecimal amount);

    int debitWithdrawPending(java.math.BigDecimal amount);

    int creditDeposit(java.math.BigDecimal amount);
}
