package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.ResourceCostHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ResourceCostHistoryRepository extends JpaRepository<ResourceCostHistory, UUID> {
    /** Newest first, with {@code from <= effectiveFrom <= to}. */
    @Query(value = """
            select h from ResourceCostHistory h left join fetch h.supplier
            where h.organizationId = :organizationId and h.resourceId = :resourceId
              and h.effectiveFrom >= :from and h.effectiveFrom <= :to
            order by h.effectiveFrom desc, h.id desc
            """,
            countQuery = """
            select count(h) from ResourceCostHistory h
            where h.organizationId = :organizationId and h.resourceId = :resourceId
              and h.effectiveFrom >= :from and h.effectiveFrom <= :to
            """)
    Page<ResourceCostHistory> findNewestFirst(@Param("organizationId") UUID organizationId,
                                              @Param("resourceId") UUID resourceId,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to,
                                              Pageable pageable);
}
