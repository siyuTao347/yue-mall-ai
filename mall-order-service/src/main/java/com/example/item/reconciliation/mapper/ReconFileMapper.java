package com.example.item.reconciliation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.reconciliation.domain.ReconFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReconFileMapper extends BaseMapper<ReconFile> {

    @Select("SELECT * FROM t_recon_file WHERE file_no = #{fileNo}")
    ReconFile selectByFileNo(@Param("fileNo") String fileNo);
}
