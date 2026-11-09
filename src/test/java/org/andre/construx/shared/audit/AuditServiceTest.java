package org.andre.construx.shared.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditServiceTest {

    private AuditEventRepository repository;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        service = new AuditService(repository, new ObjectMapper());
    }

    @Test
    void recordSavesEventWithSerializedState() {
        UUID actorId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> before = Map.of("name", "Zone A");
        Map<String, Object> after = Map.of("name", "Zone B");

        service.record("FloorPlanZone", entityId, "UPDATE", actorId, before, after);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent saved = captor.getValue();

        assertEquals("FloorPlanZone", saved.getEntityType());
        assertEquals(entityId, saved.getEntityId());
        assertEquals("UPDATE", saved.getAction());
        assertEquals(actorId, saved.getActorMembershipId());
        assertNotNull(saved.getBeforeState());
        assertNotNull(saved.getAfterState());
        assertTrue(saved.getBeforeState().contains("Zone A"));
        assertTrue(saved.getAfterState().contains("Zone B"));
        assertNotNull(saved.getOccurredAt());
    }

    @Test
    void recordWithNullActorSavesEventWithoutActor() {
        UUID entityId = UUID.randomUUID();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.record("FloorPlanZone", entityId, "CREATE", null, null, Map.of("id", entityId.toString()));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getActorMembershipId());
        assertNull(captor.getValue().getBeforeState());
    }

    @Test
    void listForEntityReturnsMappedData() {
        UUID entityId = UUID.randomUUID();
        AuditEvent event = new AuditEvent("FloorPlanZone", entityId, "CREATE",
                null, null, "{}", Instant.now());
        when(repository.findByEntityTypeAndEntityIdOrderByOccurredAtDesc("FloorPlanZone", entityId))
                .thenReturn(List.of(event));

        List<AuditService.AuditEventData> result = service.listForEntity("FloorPlanZone", entityId);

        assertEquals(1, result.size());
        assertEquals("CREATE", result.get(0).action());
        assertEquals(entityId, result.get(0).entityId());
    }
}
