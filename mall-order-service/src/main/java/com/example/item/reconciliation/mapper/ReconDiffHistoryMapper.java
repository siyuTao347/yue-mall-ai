package com.example.item.reconciliation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.reconciliation.domain.ReconDiffHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReconDiffHistoryMapper extends BaseMapper<ReconDiffHistory> {

    @Select("SELECT * FROM t_recon_diff_history WHERE diff_no = #{diffNo} ORDER BY id ASC")
    List<ReconDiffHistory> listByDiffNo(@Param("diffNo") String diffNo);
}
