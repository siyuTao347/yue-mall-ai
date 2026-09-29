package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.user.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
    int creditAvailable(Long userId, java.math.BigDecimal amount);

    int creditPending(Long userId, java.math.BigDecimal amount);

    int settleToAvailable(Long userId, java.math.BigDecimal amount);

    int freezeForWithdraw(Long userId, java.math.BigDecimal amount);

    int payoutFrozen(Long userId, java.math.BigDecimal amount);

    int unfreezeForReject(Long userId, java.math.BigDecimal amount);
}
