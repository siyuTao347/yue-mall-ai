package com.example.moneyagent.investigation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyCheckpoint implements Serializable {
    private static final long serialVersionUID = 1L;

    @Builder.Default
    private int schemaVersion = 1;
    @Builder.Default
    private String nextNode = MoneyGraphNode.VALIDATE_SCOPE.name();
    private Long lastConsumedInputSeq;
    @Builder.Default
    private List<String> snapshotRefs = new ArrayList<>();
    private String evidenceFingerprint;
    @Builder.Default
    private Map<String, String> sourceVersions = new HashMap<>();
    @Builder.Default
    private List<String> completedActionHashes = new ArrayList<>();
    private int planRounds;
    private int toolCalls;
    private int sourceRpcCalls;
    @Builder.Default
    private int nextToolCallSeq = 1;
    private String stopReason;

    // 全链路追踪上下文 (透传自上游客服/业务调用)
    private String traceId;
    private String customerRunNo;
    private String dispatchNo;

    // 运行期间沉淀的事实与状态
    private String canonicalOrderNo;
    private String scopeProofRef;
    private String orderPaymentStatus;
    private String channelStatus;
    private Boolean channelExists;
    private String channelAmount;
    private String channelCurrency;
    private Integer fundTransactionCount;
    private String ruleOutcome;
    private String ruleSummary;
    private String candidateReportNo;
    private String candidateReportHash;

    // 丰富事实快照、多渠道观察与规则 findings
    @Builder.Default
    private List<Map<String, Object>> verifiedFindings = new ArrayList<>();
    @Builder.Default
    private List<Map<String, Object>> structuredFacts = new ArrayList<>();
    @Builder.Default
    private List<Map<String, Object>> attemptObservations = new ArrayList<>();
    @Builder.Default
    private List<Map<String, Object>> refundObservations = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> reconEvidenceData = new HashMap<>();
    @Builder.Default
    private Map<String, Object> coverageInfo = new HashMap<>();
    private Boolean identityMatched;
    private Boolean amountMatched;
    private Boolean currencyMatched;
    @Builder.Default
    private List<Map<String, Object>> fundPath = new ArrayList<>();
    @Builder.Default
    private List<String> conflicts = new ArrayList<>();
    @Builder.Default
    private List<String> missingEvidence = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> stateData = new HashMap<>();
}
