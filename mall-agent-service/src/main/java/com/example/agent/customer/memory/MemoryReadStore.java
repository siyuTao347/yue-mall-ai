package com.example.agent.customer.memory;

import com.example.agent.customer.CustomerDatabase;
import com.example.agent.customer.memory.MemoryModels.*;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.List;

@Component
public class MemoryReadStore {
    private final CustomerMemoryStore memoryStore;
    private final CustomerDatabase db;

    public MemoryReadStore(CustomerMemoryStore memoryStore, CustomerDatabase db) {
        this.memoryStore = memoryStore;
        this.db = db;
    }

    public MemorySetting readSetting(long userId, MemoryDeadline deadline) {
        deadline.remainingMs();
        return memoryStore.getOrCreateSetting(userId);
    }

    public List<MemoryRecord> readActiveMemories(long userId, long epoch, int limit, MemoryDeadline deadline) {
        deadline.remainingMs();
        return memoryStore.queryActiveMemories(userId, epoch, limit);
    }
}
