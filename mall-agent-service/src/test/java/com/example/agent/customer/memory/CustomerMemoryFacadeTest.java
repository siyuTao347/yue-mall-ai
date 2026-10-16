package com.example.agent.customer.memory;

import api.agent.AgentActor;
import com.example.agent.customer.memory.MemoryModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.example.agent.customer.CustomerJson.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerMemoryFacadeTest {
    private MemoryProperties properties;
    private CustomerMemoryStore memoryStore;
    private MemoryReadStore readStore;
    private StructuredPreferenceSelector selector;
    private CustomerMemoryFacade facade;
    private final AgentActor actor = new AgentActor(100L, "USER");

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        memoryStore = mock(CustomerMemoryStore.class);
        readStore = mock(MemoryReadStore.class);
        selector = new StructuredPreferenceSelector();
        facade = new CustomerMemoryFacade(properties, memoryStore, readStore, selector);
    }

    private MemoryRecord sampleRecord(String no) {
        return MemoryRecord.of(
            no,
            MemoryType.DELIVERY_PREFERENCE,
            Polarity.PREFER,
            "DELIVERY|GLOBAL|AUTO",
            "偏好自动发货",
            object(),
            object().put("deliveryMode", "AUTO")
        );
    }

    @Test
    void recallReturnsSkippedWhenUserDisabledRead() {
        var setting = new MemorySetting(100L, "sub_100", false, true, 1L, 1L, "v1", Instant.now(), 0L, 1L);
        when(readStore.readSetting(eq(100L), any())).thenReturn(setting);

        var request = new RecallRequest("推荐点卡", null, null, true);
        var snapshot = facade.recall(actor, request, new MemoryDeadline(500));

        assertEquals(RecallStatus.SKIPPED, snapshot.status());
        assertTrue(snapshot.memories().isEmpty());
    }

    @Test
    void recallReturnsHitWhenActiveMemoriesExist() {
        var setting = new MemorySetting(100L, "sub_100", true, true, 1L, 1L, "v1", Instant.now(), 0L, 1L);
        var record = sampleRecord("M101");
        when(readStore.readSetting(eq(100L), any())).thenReturn(setting);
        when(readStore.readActiveMemories(eq(100L), eq(1L), anyInt(), any())).thenReturn(List.of(record));

        var request = new RecallRequest("推荐点卡", null, null, true);
        var snapshot = facade.recall(actor, request, new MemoryDeadline(500));

        assertEquals(RecallStatus.HIT, snapshot.status());
        assertEquals(1, snapshot.memories().size());
        assertEquals("M101", snapshot.memories().get(0).memoryNo());
        assertTrue(snapshot.summaryText().contains("自动发货"));
    }

    @Test
    void revalidateDetectsRevisionAndEpochChanges() {
        var record = sampleRecord("M101");
        var settingV1 = new MemorySetting(100L, "sub_100", true, true, 1L, 1L, "v1", Instant.now(), 0L, 1L);
        var snapshot = new MemorySnapshot(100L, 1L, 1L, RecallStatus.HIT, List.of(record), null, "summary", 10L);

        // Current setting has same epoch & revision
        when(readStore.readSetting(eq(100L), any())).thenReturn(settingV1);
        assertTrue(facade.revalidate(actor, snapshot, new MemoryDeadline(200)));

        // User updated memory setting during stream -> revision bumped to 2
        var settingV2 = new MemorySetting(100L, "sub_100", true, true, 1L, 2L, "v1", Instant.now(), 0L, 1L);
        when(readStore.readSetting(eq(100L), any())).thenReturn(settingV2);
        assertFalse(facade.revalidate(actor, snapshot, new MemoryDeadline(200)), "Revision mismatch must fail revalidation");
    }
}
