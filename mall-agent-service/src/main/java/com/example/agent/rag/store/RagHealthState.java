package com.example.agent.rag.store;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** 向量库可用性状态，供检索降级与 actuator 健康检查使用。 */
@Component
public class RagHealthState {

    private final AtomicBoolean vectorAvailable = new AtomicBoolean(false);
    private volatile String vectorVersion;

    public boolean isVectorAvailable() {
        return vectorAvailable.get();
    }

    public void markVectorAvailable(boolean available, String version) {
        this.vectorAvailable.set(available);
        this.vectorVersion = version;
    }

    public String getVectorVersion() {
        return vectorVersion;
    }
}
