package com.example.moneyagent.investigation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelPlanResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean actionNeeded;
    private String toolName;
    private String targetRef;
    private List<String> supportingFactIds;
    private String hypothesis;
    private String reason;
    private String actionHash;
}

