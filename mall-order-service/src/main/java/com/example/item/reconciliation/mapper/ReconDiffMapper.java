package com.example.item.reconciliation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.reconciliation.domain.ReconDiff;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReconDiffMapper extends BaseMapper<ReconDiff> {

    @Select("SELECT * FROM t_recon_diff WHERE diff_no = #{diffNo}")
    ReconDiff selectByDiffNo(@Param("diffNo") String diffNo);

    @Select("SELECT * FROM t_recon_diff WHERE diff_key = #{diffKey}")
    ReconDiff selectByDiffKey(@Param("diffKey") String diffKey);

    @Select("<script>" +
            "SELECT * FROM t_recon_diff " +
            "<where>" +
            "<if test='status != null and status != \"\"'> AND status = #{status} </if>" +
            "<if test='reconLayer != null and reconLayer != \"\"'> AND recon_layer = #{reconLayer} </if>" +
            "<if test='diffType != null and diffType != \"\"'> AND diff_type = #{diffType} </if>" +
            "</where>" +
            "ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<ReconDiff> listPaged(@Param("status") String status,
                              @Param("reconLayer") String reconLayer,
                              @Param("diffType") String diffType,
                              @Param("offset") int offset,
                              @Param("limit") int limit);

    @Select("<script>" +
            "SELECT COUNT(*) FROM t_recon_diff " +
            "<where>" +
            "<if test='status != null and status != \"\"'> AND status = #{status} </if>" +
            "<if test='reconLayer != null and reconLayer != \"\"'> AND recon_layer = #{reconLayer} </if>" +
            "<if test='diffType != null and diffType != \"\"'> AND diff_type = #{diffType} </if>" +
            "</where>" +
            "</script>")
    long countDiffs(@Param("status") String status,
                    @Param("reconLayer") String reconLayer,
                    @Param("diffType") String diffType);

    @Update("UPDATE t_recon_diff SET status = #{newStatus}, resolved_at = #{resolvedAt}, " +
            "waive_reason = #{waiveReason}, updated_time = #{now} WHERE diff_no = #{diffNo}")
    int updateDiffStatus(@Param("diffNo") String diffNo,
                         @Param("newStatus") String newStatus,
                         @Param("waiveReason") String waiveReason,
                         @Param("resolvedAt") LocalDateTime resolvedAt,
                         @Param("now") LocalDateTime now);
}
