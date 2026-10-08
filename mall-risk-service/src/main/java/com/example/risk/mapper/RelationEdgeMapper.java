package com.example.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.risk.entity.RelationEdge;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RelationEdgeMapper extends BaseMapper<RelationEdge> {
    @Select("""
            <script>
            SELECT * FROM t_relation_edge
            WHERE source_user_id IN
            <foreach collection="userIds" item="userId" open="(" separator="," close=")">#{userId}</foreach>
            OR target_user_id IN
            <foreach collection="userIds" item="userId" open="(" separator="," close=")">#{userId}</foreach>
            ORDER BY last_seen_time DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<RelationEdge> selectByUserIds(@Param("userIds") Collection<Long> userIds, @Param("limit") int limit);

    @Insert("INSERT INTO t_relation_edge(source_user_id, target_user_id, relation_type, weight, " +
            "first_seen_time, last_seen_time, evidence_json) VALUES(#{sourceUserId}, #{targetUserId}, " +
            "#{relationType}, #{weight}, #{now}, #{now}, #{evidenceJson}) ON DUPLICATE KEY UPDATE " +
            "hit_count = hit_count + 1, last_seen_time = VALUES(last_seen_time)")
    int upsert(@Param("sourceUserId") Long sourceUserId, @Param("targetUserId") Long targetUserId,
               @Param("relationType") String relationType, @Param("weight") int weight,
               @Param("now") LocalDateTime now, @Param("evidenceJson") String evidenceJson);
}
