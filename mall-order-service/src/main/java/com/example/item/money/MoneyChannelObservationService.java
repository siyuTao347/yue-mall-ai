package com.example.item.money;

import api.agent.money.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.payment.channel.ChannelPaymentCommands.*;
import com.example.item.payment.channel.ChannelProfileSnapshot;
import com.example.item.payment.channel.PaymentChannelStrategy;
import com.example.item.payment.channel.PaymentStrategyRegistry;
import com.example.item.payment.domain.PaymentAttempt;
import com.example.item.payment.domain.PaymentRefundOrder;
import com.example.item.payment.mapper.PaymentAttemptMapper;
import com.example.item.payment.mapper.PaymentRefundOrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Slf4j
@Service
public class MoneyChannelObservationService {

    private final PaymentAttemptMapper attemptMapper;
    private final PaymentRefundOrderMapper refundMapper;
    private final PaymentStrategyRegistry strategyRegistry;
    private final MoneyChannelObservationMapper observationMapper;
    private final ObjectMapper objectMapper;

    public MoneyChannelObservationService(PaymentAttemptMapper attemptMapper,
                                         PaymentRefundOrderMapper refundMapper,
                                         PaymentStrategyRegistry strategyRegistry,
                                         @Autowired(required = false) MoneyChannelObservationMapper observationMapper,
                                         ObjectMapper objectMapper) {
        this.attemptMapper = attemptMapper;
        this.refundMapper = refundMapper;
        this.strategyRegistry = strategyRegistry;
        this.observationMapper = observationMapper;
        this.objectMapper = objectMapper;
    }

    public MoneyChannelObservation observeChannel(MoneyAccessContext access, MoneyChannelQuery query) {
        if (query == null) {
            return MoneyChannelObservation.unavailable(null, null, null, "查询参数为空");
        }

        String scopeKey = query.getScopeKey();
        String attemptNo = query.getAttemptNo();
        String refundNo = query.getRefundNo();
        String requestId = access != null ? access.getRequestId() : null;

        try {
            if (attemptNo != null && !attemptNo.isBlank()) {
                return observePaymentAttempt(scopeKey, attemptNo, requestId);
            } else if (refundNo != null && !refundNo.isBlank()) {
                return observeRefundOrder(scopeKey, refundNo, requestId);
            } else {
                return MoneyChannelObservation.unavailable(scopeKey, null, null, "未指定 attemptNo 或 refundNo");
            }
        } catch (Exception e) {
            log.error("渠道只读观察异常, scopeKey={}, attemptNo={}, refundNo={}", scopeKey, attemptNo, refundNo, e);
            MoneyChannelObservation failed = MoneyChannelObservation.unavailable(scopeKey, attemptNo, refundNo, "渠道调用异常: " + e.getMessage());
            failed.setRawStatus("ERROR");
            return failed;
        }
    }

    private MoneyChannelObservation observePaymentAttempt(String scopeKey, String attemptNo, String requestId) throws Exception {
        PaymentAttempt attempt = attemptMapper.selectOne(new LambdaQueryWrapper<PaymentAttempt>()
                .eq(PaymentAttempt::getAttemptNo, attemptNo.trim()));
        if (attempt == null) {
            return MoneyChannelObservation.unavailable(scopeKey, attemptNo, null, "未找到指定的支付尝试记录: " + attemptNo);
        }

        // R03 修复：原 profile 缺失或无效时坚决不使用默认渠道兜底，直接返回 PROFILE_UNRESOLVED
        ChannelProfileSnapshot profile = resolveProfile(attempt.getProfileSnapshot());
        if (profile == null) {
            MoneyChannelObservation obs = MoneyChannelObservation.unavailable(scopeKey, attemptNo, null, "无法恢复原渠道商户快照 (PROFILE_UNRESOLVED)");
            obs.setErrorMsg(MoneyErrorCodes.MONEY_PROFILE_UNRESOLVED);
            return obs;
        }

        PaymentChannelStrategy strategy = strategyRegistry.require(profile.paymentMethod());
        QueryChannelPayment cmd = new QueryChannelPayment(
                attempt.getAttemptNo(),
                attempt.getOutTradeNo(),
                attempt.getChannelTradeNo(),
                profile
        );

        ChannelPaymentResult result = strategy.queryPayment(cmd);
        boolean exists = result != null && result.exists();
        // R04 修复：未确认状态保留 UNKNOWN，不凭空假设 SUCCESS
        String rawStatus = (result != null && result.status() != null)
                ? result.status()
                : (exists ? "UNKNOWN" : "NOT_FOUND");
        BigDecimal amount = result != null ? result.amount() : null;
        String currency = result != null ? result.currency() : null;

        // R04 修复：渠道响应未直接证明商户时保持未验证 (false)，不把入参当作响应证明
        boolean merchantMatched = false;
        boolean amountMatched = result != null && result.amount() != null && attempt.getAmount() != null
                && result.amount().compareTo(attempt.getAmount()) == 0;
        boolean currencyMatched = currency != null && attempt.getCurrency() != null
                && currency.equalsIgnoreCase(attempt.getCurrency());

        String obsNo = "OBS_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String channelSuccessAtStr = (result != null && result.channelSuccessAt() != null) ? result.channelSuccessAt().toString() : null;
        String observedAtStr = Instant.now().toString();

        MoneyChannelObservation observation = new MoneyChannelObservation(
                obsNo,
                scopeKey != null ? scopeKey : "ORDER:" + attempt.getOrderNo(),
                attemptNo,
                null,
                "SUCCESS",
                exists,
                rawStatus,
                amount != null ? amount : BigDecimal.ZERO,
                currency,
                profile.channelMerchantNo(),
                result != null && result.channelTradeNo() != null ? result.channelTradeNo() : attempt.getChannelTradeNo(),
                null,
                channelSuccessAtStr,
                observedAtStr,
                "MOCK",
                merchantMatched,
                amountMatched,
                currencyMatched,
                null, // 先不填 AUDIT 引用
                null
        );

        // R12 修复：审计持久化成功后才发出对应的 AUDIT 引用；失败时显式标记不可持久验证
        boolean persisted = recordAudit(obsNo, scopeKey != null ? scopeKey : "ORDER:" + attempt.getOrderNo(), attemptNo, null,
                profile.profileId(), profile.version(), requestId, observation);
        if (persisted) {
            observation.setEvidenceRef("AUDIT:" + obsNo);
        } else {
            observation.setEvidenceRef(null);
            observation.setErrorMsg("OBSERVATION_AUDIT_NOT_PERSISTED");
        }

        return observation;
    }

    private MoneyChannelObservation observeRefundOrder(String scopeKey, String refundNo, String requestId) throws Exception {
        PaymentRefundOrder refund = refundMapper.selectOne(new LambdaQueryWrapper<PaymentRefundOrder>()
                .eq(PaymentRefundOrder::getRefundNo, refundNo.trim()));
        if (refund == null) {
            return MoneyChannelObservation.unavailable(scopeKey, null, refundNo, "未找到指定的退款单记录: " + refundNo);
        }

        PaymentAttempt originalAttempt = attemptMapper.selectOne(new LambdaQueryWrapper<PaymentAttempt>()
                .eq(PaymentAttempt::getAttemptNo, refund.getOriginalAttemptNo()));

        ChannelProfileSnapshot profile = originalAttempt != null
                ? resolveProfile(originalAttempt.getProfileSnapshot())
                : null;

        // R03 修复：原退款商户配置无法恢复时报 PROFILE_UNRESOLVED，不使用默认渠道
        if (profile == null) {
            MoneyChannelObservation obs = MoneyChannelObservation.unavailable(scopeKey, null, refundNo, "无法恢复退款对应的原渠道商户快照 (PROFILE_UNRESOLVED)");
            obs.setErrorMsg(MoneyErrorCodes.MONEY_PROFILE_UNRESOLVED);
            return obs;
        }

        PaymentChannelStrategy strategy = strategyRegistry.require(profile.paymentMethod());
        QueryChannelRefund query = new QueryChannelRefund(
                refund.getRefundNo(),
                refund.getChannelRefundNo(),
                profile
        );

        ChannelRefundResult result = strategy.queryRefund(query);
        // R04 修复：区分 NOT_FOUND、成功与业务状态，不把 NOT_FOUND 解释为 exists=true
        boolean isNotFound = result != null && "NOT_FOUND".equalsIgnoreCase(result.status());
        boolean exists = result != null && result.success() && !isNotFound && !"UNKNOWN".equalsIgnoreCase(result.status());
        String rawStatus = result != null && result.status() != null ? result.status() : (exists ? "PROCESSING" : "NOT_FOUND");
        BigDecimal amount = result != null ? result.amount() : null;

        // R04 修复：ChannelRefundResult 缺少商户和币种证据，显式标为未验证 (false)，不借用本地参数当作渠道证据
        boolean amountMatched = result != null && result.amount() != null && refund.getAmount() != null
                && result.amount().compareTo(refund.getAmount()) == 0;
        boolean merchantMatched = false;
        boolean currencyMatched = false;

        String obsNo = "OBS_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String successAtStr = result != null && result.successAt() != null ? result.successAt().toString() : null;
        String observedAtStr = Instant.now().toString();

        MoneyChannelObservation observation = new MoneyChannelObservation(
                obsNo,
                scopeKey != null ? scopeKey : "ORDER:" + refund.getOrderNo(),
                refund.getOriginalAttemptNo(),
                refundNo,
                "SUCCESS",
                exists,
                rawStatus,
                amount != null ? amount : BigDecimal.ZERO,
                null,
                profile.channelMerchantNo(),
                null,
                result != null ? result.channelRefundNo() : refund.getChannelRefundNo(),
                successAtStr,
                observedAtStr,
                "MOCK",
                merchantMatched,
                amountMatched,
                currencyMatched,
                null,
                null
        );

        // R12 修复：仅在落库成功时生成 AUDIT:obsNo
        boolean persisted = recordAudit(obsNo, scopeKey != null ? scopeKey : "ORDER:" + refund.getOrderNo(),
                refund.getOriginalAttemptNo(), refundNo, profile.profileId(), profile.version(), requestId, observation);
        if (persisted) {
            observation.setEvidenceRef("AUDIT:" + obsNo);
        } else {
            observation.setEvidenceRef(null);
            observation.setErrorMsg("OBSERVATION_AUDIT_NOT_PERSISTED");
        }

        return observation;
    }

    private boolean recordAudit(String obsNo, String scopeKey, String attemptNo, String refundNo,
                                String profileId, int profileVersion, String requestId, MoneyChannelObservation observation) {
        if (observationMapper == null) return false;
        try {
            String json = objectMapper.writeValueAsString(observation);
            String hash = MoneySecuritySupport.computeFingerprint(json);
            // R11 修复：新观察审计表明确写入 UTC 时间戳
            LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
            MoneyChannelObservationEntity entity = MoneyChannelObservationEntity.builder()
                    .observationNo(obsNo)
                    .scopeKey(scopeKey != null ? scopeKey : "")
                    .attemptNo(attemptNo != null ? attemptNo : "")
                    .refundNo(refundNo)
                    .profileId(profileId != null ? profileId : "DEFAULT")
                    .profileVersion(profileVersion)
                    .requestId(requestId != null ? requestId : "REQ_LOCAL")
                    .observationJson(json)
                    .resultHash(hash)
                    .observedAt(nowUtc)
                    .createdAt(nowUtc)
                    .build();
            int rows = observationMapper.insert(entity);
            return rows > 0;
        } catch (Exception e) {
            log.warn("保存渠道观察审计记录失败, obsNo={}", obsNo, e);
            return false;
        }
    }

    private ChannelProfileSnapshot resolveProfile(String snapshotJson) {
        if (snapshotJson != null && !snapshotJson.isBlank()) {
            try {
                return objectMapper.readValue(snapshotJson, ChannelProfileSnapshot.class);
            } catch (Exception ignored) {
            }
        }
        // 坚决不使用默认配置回退！缺少或无法解析必须返回 null
        return null;
    }
}
