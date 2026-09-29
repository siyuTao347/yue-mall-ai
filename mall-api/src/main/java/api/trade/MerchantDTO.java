package api.trade;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantDTO implements Serializable {
    private Long id;
    private Long userId;
    private String merchantName;
    private String status;
    private BigDecimal totalDeposit;
    private BigDecimal frozenDeposit;
    private BigDecimal deductedDeposit;
    private LocalDateTime createdTime;
}
