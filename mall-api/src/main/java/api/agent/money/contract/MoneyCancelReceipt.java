package api.agent.money.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 资金任务取消回执
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyCancelReceipt implements Serializable {

    private String requestId;
    private String caseNo;
    private String taskNo;
    private boolean canceled;
    private String taskState;
    private String message;
}

