package api.agent.money.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 携带委托凭据的应用请求包装器
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DelegatedRequest<T> implements Serializable {

    private T payload;
    private MoneyCallerCredential credential;

    public static <T> DelegatedRequest<T> of(T payload, MoneyCallerCredential credential) {
        return new DelegatedRequest<>(payload, credential);
    }
}

