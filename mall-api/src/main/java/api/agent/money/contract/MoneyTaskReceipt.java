package api.agent.money.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 资金调查受理回执 (mall.money.receipt.v1)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyTaskReceipt implements Serializable {

    @Builder.Default
    private int schemaVersion = 1;

    private String requestId;
    private String caseNo;
    private String taskNo;
    private String contextId;
    private String acceptedAt;
    private boolean reused;
    private Long projectionRevision;
}

