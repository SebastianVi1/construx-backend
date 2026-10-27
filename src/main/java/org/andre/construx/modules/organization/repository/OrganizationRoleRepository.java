package org.andre.construx.modules.organization.repository;

import org.andre.construx.modules.organization.model.OrganizationRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRoleRepository extends JpaRepository<OrganizationRole, UUID> {
    Optional<OrganizationRole> findByOrganizationIdAndCode(UUID organizationId, String code);

    List<OrganizationRole> findByOrganizationIdOrderByCode(UUID organizationId);
}
