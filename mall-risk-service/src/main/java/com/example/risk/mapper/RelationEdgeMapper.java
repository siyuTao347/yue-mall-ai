package com.example.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.risk.entity.RelationEdge;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface RelationEdgeMapper extends BaseMapper<RelationEdge> {
    @Insert("INSERT INTO t_relation_edge(source_user_id, target_user_id, relation_type, weight, " +
            "first_seen_time, last_seen_time, evidence_json) VALUES(#{sourceUserId}, #{targetUserId}, " +
            "#{relationType}, #{weight}, #{now}, #{now}, #{evidenceJson}) ON DUPLICATE KEY UPDATE " +
            "hit_count = hit_count + 1, last_seen_time = VALUES(last_seen_time)")
    int upsert(@Param("sourceUserId") Long sourceUserId, @Param("targetUserId") Long targetUserId,
               @Param("relationType") String relationType, @Param("weight") int weight,
               @Param("now") LocalDateTime now, @Param("evidenceJson") String evidenceJson);
}
