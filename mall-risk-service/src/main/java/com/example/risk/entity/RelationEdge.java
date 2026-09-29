package com.example.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_relation_edge")
public class RelationEdge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sourceUserId;
    private Long targetUserId;
    private String relationType;
    private Integer weight;
    private Integer hitCount;
    private LocalDateTime firstSeenTime;
    private LocalDateTime lastSeenTime;
    private String evidenceJson;
}
