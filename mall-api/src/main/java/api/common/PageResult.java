package api.common;

import java.util.List;

public record PageResult<T>(List<T> records, Integer page, Integer pageSize, Long total, Boolean hasMore) {

    public static <T> PageResult<T> of(List<T> records, long total, int page, int pageSize) {
        boolean hasMore = (long) page * pageSize < total;
        return new PageResult<>(records == null ? List.of() : records, page, pageSize, total, hasMore);
    }
}
