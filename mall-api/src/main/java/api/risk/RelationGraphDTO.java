package api.risk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationGraphDTO implements Serializable {
    private Long rootUserId;
    private Integer depth;
    private Boolean truncated;
    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
}
