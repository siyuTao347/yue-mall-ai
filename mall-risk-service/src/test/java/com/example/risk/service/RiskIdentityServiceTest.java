package com.example.risk.service;

import api.risk.RelationGraphDTO;
import com.example.risk.entity.RelationEdge;
import com.example.risk.mapper.RelationEdgeMapper;
import com.example.risk.mapper.RiskIdentityMapper;
import com.example.risk.mapper.UserDeviceMapper;
import com.example.risk.mapper.UserIpMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskIdentityServiceTest {
    private RelationEdgeMapper relationMapper;
    private RiskIdentityService service;

    @BeforeEach
    void setUp() {
        relationMapper = mock(RelationEdgeMapper.class);
        service = new RiskIdentityService(mock(UserDeviceMapper.class), mock(UserIpMapper.class),
                relationMapper, mock(RiskIdentityMapper.class), new ObjectMapper(),
                new SimpleMeterRegistry());
    }

    @Test
    void graphDepthTwoUsesOneBatchQueryPerLevel() {
        when(relationMapper.selectByUserIds(any(), eq(600)))
                .thenReturn(List.of(edge(1L, 10L, 20L)))
                .thenReturn(List.of(edge(2L, 20L, 30L)));

        RelationGraphDTO result = service.getRelations(10L, 2);

        Assertions.assertEquals(2, result.getDepth());
        Assertions.assertEquals(3, result.getNodes().size());
        Assertions.assertEquals(2, result.getEdges().size());
        Assertions.assertFalse(result.getEdges().get(0).containsKey("evidence"));
        verify(relationMapper, times(2)).selectByUserIds(any(), eq(600));
    }

    private RelationEdge edge(Long id, Long source, Long target) {
        RelationEdge edge = new RelationEdge();
        edge.setId(id);
        edge.setSourceUserId(source);
        edge.setTargetUserId(target);
        edge.setRelationType("TRADED");
        edge.setWeight(10);
        edge.setHitCount(1);
        return edge;
    }
}
