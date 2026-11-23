package org.andre.construx.modules.organization.repository;

import jakarta.persistence.LockModeType;
import org.andre.construx.modules.organization.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    boolean existsByName(String name);

    /**
     * The company with its row locked until the transaction ends. Writes that check something across the company's
     * roles (the Custom role limit, unique names) take it first, so two of them run one after the other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.id = :id")
    Optional<Organization> findForUpdate(UUID id);
}
