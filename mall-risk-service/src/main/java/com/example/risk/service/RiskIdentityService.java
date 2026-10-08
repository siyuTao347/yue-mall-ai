package com.example.risk.service;

import api.risk.RelationGraphDTO;
import api.risk.RiskEvaluateRequest;
import com.example.risk.entity.RelationEdge;
import com.example.risk.entity.RiskEvent;
import com.example.risk.mapper.RelationEdgeMapper;
import com.example.risk.mapper.RiskIdentityMapper;
import com.example.risk.mapper.UserDeviceMapper;
import com.example.risk.mapper.UserIpMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RiskIdentityService {
    private static final int MAX_NODES = 100;
    private static final int MAX_EDGES = 300;
    private static final int QUERY_LIMIT = 600;

    private final UserDeviceMapper deviceMapper;
    private final UserIpMapper ipMapper;
    private final RelationEdgeMapper relationMapper;
    private final RiskIdentityMapper identityMapper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public RiskIdentityService(UserDeviceMapper deviceMapper, UserIpMapper ipMapper,
                               RelationEdgeMapper relationMapper, RiskIdentityMapper identityMapper,
                               ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.deviceMapper = deviceMapper;
        this.ipMapper = ipMapper;
        this.relationMapper = relationMapper;
        this.identityMapper = identityMapper;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public void applyConfirmedEvent(RiskEvent event) {
        if (event == null || event.getUserId() == null) {
            return;
        }
        LocalDateTime now = event.getConfirmedTime() == null ? LocalDateTime.now() : event.getConfirmedTime();
        if (event.getDeviceHash() != null) {
            deviceMapper.upsert(event.getUserId(), event.getDeviceHash(), now);
        }
        if (event.getIpHash() != null) {
            ipMapper.upsert(event.getUserId(), event.getIpHash(), now);
        }
        if ("ORDER".equals(event.getScene())) {
            Map<String, Object> payload = payload(event.getPayloadJson());
            Long sellerId = asLong(payload.get("sellerId"));
            if (sellerId != null && !sellerId.equals(event.getUserId())) {
                String relation = buyerSellerRelation(event.getUserId(), sellerId,
                        event.getDeviceHash(), event.getIpHash());
                upsertEdge(event.getUserId(), sellerId, relation, event.getEventNo(), now);
            }
        }
    }

    public String buyerSellerRelation(Long buyerId, Long sellerId, String deviceHash, String ipHash) {
        return buyerSellerRelation(buyerId, sellerId, deviceHash, ipHash, new RiskQueryTracker());
    }

    public String buyerSellerRelation(Long buyerId, Long sellerId, String deviceHash, String ipHash,
                                      RiskQueryTracker tracker) {
        if (buyerId == null || sellerId == null) {
            return "NONE";
        }
        if (buyerId.equals(sellerId)) {
            return "SAME_USER";
        }
        if (deviceHash != null || ipHash != null) {
            String relation = identityMapper.sameDeviceOrIp(sellerId, deviceHash, ipHash,
                    LocalDateTime.now().minusDays(30));
            tracker.increment();
            if (!"NONE".equals(relation)) {
                return relation;
            }
        }
        if (identityMapper.existsEdge(Math.min(buyerId, sellerId), Math.max(buyerId, sellerId))) {
            tracker.increment();
            return "TRADED";
        }
        tracker.increment();
        return "NONE";
    }

    public String buyerSellerRelation(RiskEvaluateRequest request) {
        return buyerSellerRelation(request, new RiskQueryTracker());
    }

    public String buyerSellerRelation(RiskEvaluateRequest request, RiskQueryTracker tracker) {
        Map<String, Object> payload = request.getPayload() == null ? Map.of() : request.getPayload();
        Long sellerId = asLong(payload.get("sellerId"));
        return buyerSellerRelation(request.getUserId(), sellerId,
                request.getDeviceHash(), request.getIpHash(), tracker);
    }

    public RelationGraphDTO getRelations(Long userId, int depth) {
        return Timer.builder("relation_graph_duration_seconds")
                .register(meterRegistry)
                .record(() -> relations(userId, depth));
    }

    private RelationGraphDTO relations(Long userId, int depth) {
        if (userId == null) {
            return RelationGraphDTO.builder()
                    .depth(1)
                    .truncated(false)
                    .nodes(List.of())
                    .edges(List.of())
                    .build();
        }
        int safeDepth = Math.min(Math.max(depth, 1), 2);
        Set<Long> nodes = new LinkedHashSet<>();
        nodes.add(userId);
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<Long> edgeIds = new LinkedHashSet<>();
        Set<Long> frontier = Set.of(userId);
        boolean truncated = false;
        for (int level = 0; level < safeDepth; level++) {
            List<RelationEdge> levelEdges = relationMapper.selectByUserIds(frontier, QUERY_LIMIT);
            Set<Long> next = new LinkedHashSet<>();
            boolean limitHit = levelEdges.size() >= QUERY_LIMIT;
            for (RelationEdge edge : levelEdges) {
                if (nodes.size() < MAX_NODES) {
                    if (nodes.add(edge.getSourceUserId())) {
                        next.add(edge.getSourceUserId());
                    }
                    if (nodes.size() < MAX_NODES && nodes.add(edge.getTargetUserId())) {
                        next.add(edge.getTargetUserId());
                    }
                }
                if (edgeIds.add(edge.getId()) && edges.size() < MAX_EDGES) {
                    edges.add(edgeMap(edge));
                }
            }
            truncated = truncated || limitHit || nodes.size() >= MAX_NODES || edges.size() >= MAX_EDGES;
            if (truncated || next.isEmpty()) {
                break;
            }
            frontier = next;
        }
        return RelationGraphDTO.builder()
                .rootUserId(userId)
                .depth(safeDepth)
                .truncated(truncated)
                .nodes(nodes.stream().map(id -> Map.<String, Object>of("userId", id)).toList())
                .edges(edges)
                .build();
    }

    private void upsertEdge(Long buyerId, Long sellerId, String relation, String eventNo, LocalDateTime now) {
        if ("NONE".equals(relation)) {
            return;
        }
        Long source = Math.min(buyerId, sellerId);
        Long target = Math.max(buyerId, sellerId);
        int weight = switch (relation) {
            case "SAME_USER" -> 100;
            case "SAME_DEVICE" -> 80;
            case "SAME_IP" -> 40;
            default -> 10;
        };
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("relation", relation);
        evidence.put("events", List.of(eventNo));
        relationMapper.upsert(source, target, relation, weight, now, writeJson(evidence));
    }

    private Map<String, Object> edgeMap(RelationEdge edge) {
        Map<String, Object> result = new HashMap<>();
        result.put("sourceUserId", edge.getSourceUserId());
        result.put("targetUserId", edge.getTargetUserId());
        result.put("relationType", edge.getRelationType());
        result.put("weight", edge.getWeight());
        result.put("hitCount", edge.getHitCount());
        return result;
    }

    private Map<String, Object> payload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("关系证据序列化失败", e);
        }
    }
}
