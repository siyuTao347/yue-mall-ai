package com.example.moneyagent.investigation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelPlanContext implements Serializable {
    private static final long serialVersionUID = 1L;

    private String caseNo;
    private String taskNo;
    private String problemFamily;
    private String scopeKey;
    private String targetRef;
    private int currentRound;
    private int maxRounds;
    private List<String> completedActionHashes;
    private Map<String, Object> observedFacts;
    private String namedScenario;
}

