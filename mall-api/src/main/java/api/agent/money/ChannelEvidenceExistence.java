package api.agent.money;

import java.io.Serializable;

/**
 * 渠道事实存在性枚举
 */
public enum ChannelEvidenceExistence implements Serializable {
    EXISTS,
    NOT_FOUND,
    UNKNOWN
}

