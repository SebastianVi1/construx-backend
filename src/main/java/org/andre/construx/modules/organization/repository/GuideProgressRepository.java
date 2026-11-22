package org.andre.construx.modules.organization.repository;

import org.andre.construx.modules.organization.model.GuideProgress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Keyed by the membership id: a member has at most one guide progress row per organization. */
public interface GuideProgressRepository extends JpaRepository<GuideProgress, UUID> {
}
