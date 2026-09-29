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
public class SensitiveWordHitDTO implements Serializable {
    private String category;
    private String wordCode;
    private String contentHash;
    private Integer contentLength;
}
