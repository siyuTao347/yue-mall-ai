package api.audit;

/**
 * 业务操作类型枚举
 */
public enum BusinessType {
    /**
     * 其它
     */
    OTHER,

    /**
     * 新增 / 创建
     */
    INSERT,

    /**
     * 修改 / 更新
     */
    UPDATE,

    /**
     * 删除
     */
    DELETE,

    /**
     * 查询
     */
    SELECT,

    /**
     * 导出
     */
    EXPORT,

    /**
     * 导入
     */
    IMPORT,

    /**
     * 强退 / 授权
     */
    AUTH
}
