package api.common;

import java.time.Duration;
import java.time.LocalDateTime;

public record TimeRangeQuery(LocalDateTime fromTime, LocalDateTime toTime) {

    public void validate(int maxDays) {
        if (maxDays < 1) {
            throw new IllegalArgumentException("时间范围配置不合法");
        }
        if (fromTime != null && toTime != null) {
            if (fromTime.isAfter(toTime)) {
                throw new IllegalArgumentException("fromTime 不能晚于 toTime");
            }
            if (Duration.between(fromTime, toTime).toDays() > maxDays) {
                throw new IllegalArgumentException("时间范围最长 " + maxDays + " 天");
            }
        }
    }
}
