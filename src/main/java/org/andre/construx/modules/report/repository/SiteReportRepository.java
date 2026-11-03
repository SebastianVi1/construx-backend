package org.andre.construx.modules.report.repository;

import org.andre.construx.modules.report.model.SiteReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SiteReportRepository extends JpaRepository<SiteReport, UUID> {
    List<SiteReport> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    Optional<SiteReport> findByIdAndProjectId(UUID id, UUID projectId);
}
