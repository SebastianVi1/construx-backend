package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.ResourceCostHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Insert and read only: Cost history entries are never updated or deleted. */
public interface ResourceCostHistoryRepository extends JpaRepository<ResourceCostHistory, java.util.UUID>,
        JpaSpecificationExecutor<ResourceCostHistory> {
    /** Callers always include an organization filter in {@code spec}. */
    @Override
    @EntityGraph(attributePaths = {"supplier", "recordedBy", "recordedBy.userAccount"})
    Page<ResourceCostHistory> findAll(Specification<ResourceCostHistory> spec, Pageable pageable);
}
