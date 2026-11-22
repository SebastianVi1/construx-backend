package org.andre.construx.modules.organization.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.GuideProgress;
import org.andre.construx.modules.organization.model.GuideStepStatus;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.repository.GuideProgressRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The caller's own getting-started guide progress in one organization (docs/guide.md). Being an active member, which
 * the {@link OrgScope} already proved, is the only permission: the progress is always the caller's own membership.
 */
@Service
public class GuideService {
    private final GuideProgressRepository progresses;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;

    public GuideService(GuideProgressRepository progresses, EntityManager entityManager,
                        PlatformTransactionManager transactionManager) {
        this.progresses = progresses;
        this.entityManager = entityManager;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record GuideData(boolean started, Map<String, GuideStepStatus> steps, Instant dismissedAt,
                            Instant updatedAt) {
        static final GuideData NOT_STARTED = new GuideData(false, Map.of(), null, null);

        static GuideData from(GuideProgress progress) {
            return new GuideData(true, new TreeMap<>(progress.getSteps()), progress.getDismissedAt(),
                    progress.getUpdatedAt());
        }
    }

    /** Never creates a row: no row is what tells the dashboard the member has never seen the guide. */
    @Transactional(readOnly = true)
    public GuideData get(OrgScope org) {
        return progresses.findById(org.membershipId()).map(GuideData::from).orElse(GuideData.NOT_STARTED);
    }

    public GuideData mark(OrgScope org, String stepId, GuideStepStatus status) {
        return write(org.membershipId(), progress -> progress.mark(stepId, status));
    }

    public GuideData dismiss(OrgScope org, boolean dismissed) {
        return write(org.membershipId(), progress -> progress.dismiss(dismissed));
    }

    private GuideData write(UUID membershipId, Consumer<GuideProgress> change) {
        try {
            return writeOnce(membershipId, change);
        } catch (DataIntegrityViolationException collision) {
            // The failed transaction is over; reacquire a fresh membership and progress in a new transaction.
            return writeOnce(membershipId, change);
        }
    }

    private GuideData writeOnce(UUID membershipId, Consumer<GuideProgress> change) {
        return transactions.execute(ignored -> {
            // Every write to one member uses the same row lock, including writes before progress exists.
            OrganizationMembership membership = entityManager.find(OrganizationMembership.class, membershipId,
                    LockModeType.PESSIMISTIC_WRITE);
            if (membership == null) throw new ResourceNotFoundException("Organization not found");
            GuideProgress progress = progresses.findById(membershipId)
                    .orElseGet(() -> new GuideProgress(membership));
            change.accept(progress);
            return GuideData.from(progresses.saveAndFlush(progress));
        });
    }
}
