package org.andre.construx.modules.organization.repository;

import java.util.UUID;

/** How many rows (memberships, invitations) reference one role. */
public interface RoleCount {
    UUID getRoleId();

    long getCount();
}
