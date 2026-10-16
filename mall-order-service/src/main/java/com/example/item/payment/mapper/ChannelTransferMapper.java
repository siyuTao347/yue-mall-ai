package com.example.item.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.payment.domain.ChannelTransfer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChannelTransferMapper extends BaseMapper<ChannelTransfer> {

    @Select("SELECT * FROM t_channel_transfer WHERE transfer_no = #{transferNo}")
    ChannelTransfer selectByTransferNo(@Param("transferNo") String transferNo);
}
