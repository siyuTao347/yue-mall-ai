package com.example.item.reconciliation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.reconciliation.domain.ReconMatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReconMatchMapper extends BaseMapper<ReconMatch> {

    @Select("SELECT * FROM t_recon_match WHERE run_no = #{runNo} ORDER BY id ASC")
    List<ReconMatch> listByRunNo(@Param("runNo") String runNo);
}
