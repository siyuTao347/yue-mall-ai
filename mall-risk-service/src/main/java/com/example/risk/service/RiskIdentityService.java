package com.example.risk.service;

import api.risk.RiskEvaluateRequest;
import api.risk.RelationGraphDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.risk.entity.RelationEdge;
import com.example.risk.entity.RiskEvent;
import com.example.risk.entity.UserDevice;
import com.example.risk.entity.UserIp;
import com.example.risk.mapper.RelationEdgeMapper;
import com.example.risk.mapper.UserDeviceMapper;
import com.example.risk.mapper.UserIpMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RiskIdentityService {
    private final UserDeviceMapper deviceMapper;
    private final UserIpMapper ipMapper;
    private final RelationEdgeMapper relationMapper;
    private final ObjectMapper objectMapper;

    public RiskIdentityService(UserDeviceMapper deviceMapper, UserIpMapper ipMapper,
                               RelationEdgeMapper relationMapper, ObjectMapper objectMapper) {
        this.deviceMapper = deviceMapper;
        this.ipMapper = ipMapper;
        this.relationMapper = relationMapper;
        this.objectMapper = objectMapper;
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
        if (buyerId == null || sellerId == null) {
            return "NONE";
        }
        if (buyerId.equals(sellerId)) {
            return "SAME_USER";
        }
        if (deviceHash != null && sharesDeviceHash(deviceMapper.selectList(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getDeviceHash, deviceHash)
                .ge(UserDevice::getLastSeenTime, LocalDateTime.now().minusDays(30))), sellerId)) {
            return "SAME_DEVICE";
        }
        if (ipHash != null && sharesIpHash(ipMapper.selectList(new LambdaQueryWrapper<UserIp>()
                .eq(UserIp::getIpHash, ipHash)
                .ge(UserIp::getLastSeenTime, LocalDateTime.now().minusDays(30))), sellerId)) {
            return "SAME_IP";
        }
        return "NONE";
    }

    public String buyerSellerRelation(RiskEvaluateRequest request) {
        Map<String, Object> payload = request.getPayload() == null ? Map.of() : request.getPayload();
        Long sellerId = asLong(payload.get("sellerId"));
        return buyerSellerRelation(request.getUserId(), sellerId,
                request.getDeviceHash(), request.getIpHash());
    }

    public RelationGraphDTO getRelations(Long userId, int depth) {
        int safeDepth = Math.min(Math.max(depth, 1), 2);
        Set<Long> nodes = new HashSet<>();
        nodes.add(userId);
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<Long> edgeIds = new HashSet<>();
        Set<Long> frontier = Set.of(userId);
        for (int level = 0; level < safeDepth; level++) {
            Set<Long> next = new HashSet<>();
            for (Long current : frontier) {
                for (RelationEdge edge : relationMapper.selectList(new LambdaQueryWrapper<RelationEdge>()
                        .and(wrapper -> wrapper.eq(RelationEdge::getSourceUserId, current)
                                .or().eq(RelationEdge::getTargetUserId, current)))) {
                    Long other = edge.getSourceUserId().equals(current)
                            ? edge.getTargetUserId() : edge.getSourceUserId();
                    if (nodes.add(other)) {
                        next.add(other);
                    }
                    if (edgeIds.add(edge.getId()) && edges.size() < 300) {
                        edges.add(edgeMap(edge));
                    }
                }
            }
            if (nodes.size() >= 100) {
                break;
            }
            frontier = next;
        }
        List<Map<String, Object>> nodeList = nodes.stream().limit(100)
                .map(id -> Map.<String, Object>of("userId", id))
                .toList();
        return RelationGraphDTO.builder()
                .rootUserId(userId)
                .depth(safeDepth)
                .truncated(nodes.size() > 100 || edges.size() >= 300)
                .nodes(nodeList)
                .edges(edges)
                .build();
    }

    private boolean sharesDeviceHash(List<UserDevice> rows, Long userId) {
        return rows.stream().anyMatch(row -> userId.equals(row.getUserId()));
    }

    private boolean sharesIpHash(List<UserIp> rows, Long userId) {
        return rows.stream().anyMatch(row -> userId.equals(row.getUserId()));
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
        result.put("evidence", edge.getEvidenceJson());
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
            throw new IllegalStateException("风控证据序列化失败", e);
        }
    }
}
