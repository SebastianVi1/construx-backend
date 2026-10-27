package org.andre.construx.modules.organization.repository;

import org.andre.construx.modules.organization.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
