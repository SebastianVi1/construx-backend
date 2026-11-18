package org.andre.construx.modules.pricing.repository;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.andre.construx.modules.pricing.model.Resource;
import org.andre.construx.modules.pricing.model.ResourceCostHistory;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Organization-filtered queries for the paged Resource and Cost history lists. */
public final class ResourceSpecifications {
    private ResourceSpecifications() {
    }

    /**
     * Null arguments do not filter. {@code pattern} is a lower-case LIKE pattern escaped with {@code \},
     * matched against code, name and description.
     */
    public static Specification<Resource> resources(UUID organizationId, Collection<ResourceType> types,
                                                    String pattern, UUID familyId, UUID unitId, UUID supplierId,
                                                    Boolean composite, boolean includeArchived,
                                                    Boolean missingFromImport, Boolean discontinued) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("organization").get("id"), organizationId));
            if (types != null && !types.isEmpty()) {
                where.add(root.get("type").in(types));
            }
            if (pattern != null) {
                Expression<String> description = cb.coalesce(root.get("description"), "");
                where.add(cb.or(cb.like(cb.lower(root.get("code")), pattern, '\\'),
                        cb.like(cb.lower(root.get("name")), pattern, '\\'),
                        cb.like(cb.lower(description), pattern, '\\')));
            }
            if (familyId != null) {
                where.add(cb.equal(root.get("family").get("id"), familyId));
            }
            if (unitId != null) {
                where.add(cb.equal(root.get("unit").get("id"), unitId));
            }
            if (supplierId != null) {
                where.add(cb.equal(root.get("supplier").get("id"), supplierId));
            }
            if (composite != null) {
                where.add(cb.equal(root.get("composite"), composite));
            }
            if (!includeArchived) {
                where.add(cb.isNull(root.get("archivedAt")));
            }
            if (missingFromImport != null) {
                where.add(missingFromImport ? cb.isNotNull(root.get("missingFromImportAt"))
                        : cb.isNull(root.get("missingFromImportAt")));
            }
            if (discontinued != null) {
                where.add(cb.equal(root.get("discontinued"), discontinued));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** Entries of one Resource with {@code from <= effectiveFrom <= to}; null bounds are open. */
    public static Specification<ResourceCostHistory> costs(UUID organizationId, UUID resourceId, Instant from,
                                                           Instant to) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("organization").get("id"), organizationId));
            where.add(cb.equal(root.get("resource").get("id"), resourceId));
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("effectiveFrom"), from));
            }
            if (to != null) {
                where.add(cb.lessThanOrEqualTo(root.get("effectiveFrom"), to));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
