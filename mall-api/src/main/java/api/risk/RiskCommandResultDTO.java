package api.risk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskCommandResultDTO implements Serializable {
    private String commandNo;
    private String caseNo;
    private String scene;
    private Boolean success;
    private Boolean updated;
    private String message;
}
