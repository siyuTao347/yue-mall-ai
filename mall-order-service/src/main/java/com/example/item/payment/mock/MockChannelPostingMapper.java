package com.example.item.payment.mock;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MockChannelPostingMapper extends BaseMapper<MockChannelPosting> {

    @Select("SELECT COALESCE(MAX(sequence_no), 0) FROM t_mock_channel_posting WHERE account_id = #{accountId}")
    long getMaxSequence(@Param("accountId") String accountId);

    @Select("SELECT balance_after FROM t_mock_channel_posting WHERE account_id = #{accountId} ORDER BY sequence_no DESC LIMIT 1")
    BigDecimal getLatestBalance(@Param("accountId") String accountId);

    @Select("SELECT * FROM t_mock_channel_posting WHERE account_id = #{accountId} " +
            "AND channel_business_date = #{businessDate} ORDER BY sequence_no ASC")
    List<MockChannelPosting> listByDate(@Param("accountId") String accountId,
                                        @Param("businessDate") LocalDate businessDate);

    @Select("SELECT * FROM t_mock_channel_posting WHERE account_id = #{accountId} " +
            "AND sequence_no > #{cursor} AND sequence_no <= #{highWatermark} ORDER BY sequence_no ASC")
    List<MockChannelPosting> selectIncremental(@Param("accountId") String accountId,
                                               @Param("cursor") long cursor,
                                               @Param("highWatermark") long highWatermark);
}
