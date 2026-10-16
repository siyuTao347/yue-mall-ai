package com.example.item.reconciliation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.reconciliation.domain.ReconChannelItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReconChannelItemMapper extends BaseMapper<ReconChannelItem> {

    @Select("SELECT * FROM t_recon_channel_item WHERE file_id = #{fileId} ORDER BY line_no ASC")
    List<ReconChannelItem> listByFileId(@Param("fileId") Long fileId);
}
