package com.example.item.reconciliation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.reconciliation.domain.ReconRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReconRunMapper extends BaseMapper<ReconRun> {

    @Select("SELECT * FROM t_recon_run WHERE run_no = #{runNo}")
    ReconRun selectByRunNo(@Param("runNo") String runNo);

    @Select("SELECT COALESCE(MAX(run_seq), 0) FROM t_recon_run WHERE task_no = #{taskNo}")
    int getMaxRunSeq(@Param("taskNo") String taskNo);

    @Select("SELECT * FROM t_recon_run ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ReconRun> listPaged(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM t_recon_run")
    long countRuns();
}
