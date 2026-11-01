package com.example.moneyagent.investigation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyReplayResult {
    private String taskNo;
    private String caseNo;
    private String problemFamily;
    private String originalOutcome;
    private String replayedOutcome;
    private boolean match;
    private int snapshotCount;
    private int toolCallCount;
    private String evidenceFingerprint;
    private List<Map<String, Object>> replayedFindings;
    private String replayedSummary;
    private LocalDateTime replayedAt;
}

