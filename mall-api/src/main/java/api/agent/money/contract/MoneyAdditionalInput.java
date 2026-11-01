package api.agent.money.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 资金调查补充材料输入 (mall.money.additional-input.v1)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyAdditionalInput implements Serializable {

    public static final String SCHEMA_V1 = "mall.money.additional-input.v1";

    @Builder.Default
    private String schema = SCHEMA_V1;

    private String requestId;
    private String caseNo;
    private String taskNo;
    private String inputRequestNo;
    private Long expectedInputVersion;
    private Map<String, Object> answers;
    private String sourceMessageRef;
}

