package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.FasarParameterSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FasarParameterSetRepository extends JpaRepository<FasarParameterSet, UUID> {
    Optional<FasarParameterSet> findByOrganizationId(UUID organizationId);
}
