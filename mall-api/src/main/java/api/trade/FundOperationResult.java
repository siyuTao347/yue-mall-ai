package api.trade;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundOperationResult implements Serializable {
    private boolean success;
    private String message;
    private String transactionNo;

    public static FundOperationResult success(String transactionNo) {
        return FundOperationResult.builder().success(true).message("SUCCESS").transactionNo(transactionNo).build();
    }

    public static FundOperationResult fail(String message) {
        return FundOperationResult.builder().success(false).message(message).build();
    }
}
