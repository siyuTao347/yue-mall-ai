package api.agent.money.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 资金任务定位引用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyTaskReference implements Serializable {
    private String taskNo;
    private String caseNo;

    public static MoneyTaskReference of(String taskNo) {
        return new MoneyTaskReference(taskNo, null);
    }

    public static MoneyTaskReference of(String taskNo, String caseNo) {
        return new MoneyTaskReference(taskNo, caseNo);
    }
}

