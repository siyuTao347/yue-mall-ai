package com.example.item.reconciliation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.reconciliation.domain.ReconLocalItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReconLocalItemMapper extends BaseMapper<ReconLocalItem> {

    @Select("SELECT * FROM t_recon_local_item WHERE run_no = #{runNo} ORDER BY id ASC")
    List<ReconLocalItem> listByRunNo(@Param("runNo") String runNo);
}
