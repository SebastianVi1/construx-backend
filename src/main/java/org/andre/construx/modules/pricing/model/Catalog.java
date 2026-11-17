package org.andre.construx.modules.pricing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** The one Catalog of an organization; Resources, Units and Families reference the organization directly. */
@Entity
@Table(name = "catalog")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Catalog {
    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private UUID organizationId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Instant createdAt;

    /** The Catalog created by the organization's first catalog write; MXN only in v1. */
    public static Catalog open(UUID organizationId, Instant at) {
        Catalog catalog = new Catalog();
        catalog.id = UUID.randomUUID();
        catalog.organizationId = organizationId;
        catalog.currency = "MXN";
        catalog.createdAt = at;
        return catalog;
    }
}
