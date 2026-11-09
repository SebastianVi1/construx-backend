package org.andre.construx.shared.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public record AuditEventData(
            UUID id,
            String entityType,
            UUID entityId,
            String action,
            UUID actorMembershipId,
            String beforeState,
            String afterState,
            Instant occurredAt
    ) {}

    private final AuditEventRepository repository;
    private final ObjectMapper mapper;

    public AuditService(AuditEventRepository repository,
                        ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public void record(String entityType, UUID entityId, String action,
                       UUID actorMembershipId, Object beforeState, Object afterState) {
        String before = serialize(beforeState);
        String after = serialize(afterState);

        AuditEvent event = new AuditEvent(entityType, entityId, action, actorMembershipId, before, after, Instant.now());
        repository.save(event);
    }

    public List<AuditEventData> listForEntity(String entityType, UUID entityId) {
        return repository.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(entityType, entityId)
                .stream()
                .map(e -> new AuditEventData(
                        e.getId(),
                        e.getEntityType(),
                        e.getEntityId(),
                        e.getAction(),
                        e.getActorMembershipId(),
                        e.getBeforeState(),
                        e.getAfterState(),
                        e.getOccurredAt()
                ))
                .toList();
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize audit state for entity", e);
            return null;
        }
    }
}
