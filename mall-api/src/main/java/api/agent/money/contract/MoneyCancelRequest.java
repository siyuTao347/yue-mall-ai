package api.agent.money.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 资金任务取消控制请求 (mall.money.control.v1)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyCancelRequest implements Serializable {

    private String requestId;
    private String caseNo;
    private String taskNo;
    private Long expectedTaskVersion;
    private String reason;
}

