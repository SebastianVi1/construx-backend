package org.andre.construx.modules.organization.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.springframework.stereotype.Component;

/**
 * A role's optimistic-lock {@code version} moves by exactly one per write, so a client holding an older version gets
 * {@code ROLE_CHANGED} (contract roles §5.4). A write that changes the role's own row moves it when flushed; one that
 * only changes permission rows (they are not part of that row) would not, so the version is then forced up once.
 */
@Component
class RoleVersions {
    private final EntityManager entityManager;

    RoleVersions(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Writes the role's pending changes and leaves its version one above what it was before them. */
    void raise(OrganizationRole role) {
        long before = role.getVersion();
        entityManager.flush();
        if (role.getVersion() == before) {
            entityManager.lock(role, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        }
    }
}
