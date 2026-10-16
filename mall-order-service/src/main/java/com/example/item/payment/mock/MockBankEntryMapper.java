package com.example.item.payment.mock;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MockBankEntryMapper extends BaseMapper<MockBankEntry> {

    @Select("SELECT * FROM t_mock_bank_entry WHERE account_id = #{accountId} " +
            "AND value_date = #{valueDate} ORDER BY entry_seq ASC")
    List<MockBankEntry> listByDate(@Param("accountId") String accountId, @Param("valueDate") LocalDate valueDate);

    @Select("SELECT * FROM t_mock_bank_entry WHERE external_reference = #{ref} ORDER BY entry_seq ASC")
    List<MockBankEntry> listByReference(@Param("ref") String ref);
}
