package api.risk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskCommandDTO implements Serializable {
    private String commandNo;
    private String decisionNo;
    private String caseNo;
    private String scene;
    private String bizNo;
    private Long itemId;
    private String orderNo;
    private String withdrawNo;
    private Long merchantId;
    private String command;
    private String reason;
    private Long operatorId;
    private Map<String, Object> actionParams;
}
