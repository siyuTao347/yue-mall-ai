package api.common;

public record PageQuery(Integer page, Integer pageSize) {

    public static PageQuery of(Integer page, Integer pageSize, int defaultPageSize, int maxPageSize) {
        int safePage = page == null ? 1 : page;
        int safePageSize = pageSize == null ? defaultPageSize : pageSize;
        if (safePage < 1) {
            throw new IllegalArgumentException("page 最小值为 1");
        }
        if (defaultPageSize < 1 || maxPageSize < 1 || defaultPageSize > maxPageSize) {
            throw new IllegalArgumentException("分页配置不合法");
        }
        if (safePageSize < 1 || safePageSize > maxPageSize) {
            throw new IllegalArgumentException("pageSize 必须在 1 到 " + maxPageSize + " 之间");
        }
        return new PageQuery(safePage, safePageSize);
    }
}
