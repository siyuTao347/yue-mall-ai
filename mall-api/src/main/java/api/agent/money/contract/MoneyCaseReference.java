package api.agent.money.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 资金案件定位引用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyCaseReference implements Serializable {
    private String caseNo;

    public static MoneyCaseReference of(String caseNo) {
        return new MoneyCaseReference(caseNo);
    }
}

