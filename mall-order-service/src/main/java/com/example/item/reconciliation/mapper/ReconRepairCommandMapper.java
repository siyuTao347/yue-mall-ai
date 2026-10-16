package com.example.item.reconciliation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.reconciliation.domain.ReconRepairCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReconRepairCommandMapper extends BaseMapper<ReconRepairCommand> {

    @Select("SELECT * FROM t_recon_repair_command WHERE diff_no = #{diffNo} ORDER BY id ASC")
    List<ReconRepairCommand> listByDiffNo(@Param("diffNo") String diffNo);

    @Select("SELECT * FROM t_recon_repair_command WHERE command_no = #{commandNo}")
    ReconRepairCommand selectByCommandNo(@Param("commandNo") String commandNo);
}
