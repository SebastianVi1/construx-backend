package org.andre.construx.modules.organization.service;

import java.util.Comparator;
import java.util.UUID;

/**
 * The one order in which a transaction takes more than one role lock: by id as the database sorts uuids (unsigned
 * bytes, which is also the order of their hex strings), so writes that need the same roles wait for each other
 * instead of deadlocking. Role locks always come before membership and company locks.
 */
final class RoleLockOrder {
    static final Comparator<UUID> BY_ID = Comparator.comparing(UUID::toString);

    private RoleLockOrder() {
    }
}
