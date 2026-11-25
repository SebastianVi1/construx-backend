package org.andre.construx.modules.messaging.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The menu conversation of one Linked number (contract §2 {@code whatsapp_session}): the step it is on and the choices
 * made so far. Each choice moves it to the next step and forgets the choices that came after it. {@code updatedAt} is
 * the last message, where idle expiry counts from.
 */
@Entity
@Table(name = "whatsapp_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationSession {
    @Id
    @Column(name = "wa_id", length = 20)
    private String waId;

    @Column(name = "user_account_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SessionStep step;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "project_id")
    private UUID siteId;

    @Column(name = "goal_id")
    private UUID phaseId;

    @Column(name = "activity_id")
    private UUID taskId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private FieldAction action;

    /** The page of the list on screen ("See more"); 0 is the first. */
    @Column(name = "task_page", nullable = false)
    private int page;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A new conversation at the Site list (step A). */
    public ConversationSession(String waId, UUID userId, Instant now) {
        this.waId = Objects.requireNonNull(waId);
        this.userId = Objects.requireNonNull(userId);
        this.step = SessionStep.PICK_SITE;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public boolean belongsTo(UUID userId) {
        return this.userId.equals(userId);
    }

    /** Another message arrived. */
    public void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now);
    }

    /** Step B for a Site, from its first page. */
    public void chooseSite(UUID organizationId, UUID siteId) {
        this.organizationId = Objects.requireNonNull(organizationId);
        this.siteId = Objects.requireNonNull(siteId);
        this.step = SessionStep.PICK_TASK;
        this.page = 0;
        this.phaseId = null;
        this.taskId = null;
        this.action = null;
    }

    public void showPage(int page) {
        this.page = Math.max(0, page);
    }

    /** Step C for a Task of the chosen Site. */
    public void chooseTask(UUID phaseId, UUID taskId) {
        requireStep(SessionStep.PICK_TASK);
        this.phaseId = Objects.requireNonNull(phaseId);
        this.taskId = Objects.requireNonNull(taskId);
        this.step = SessionStep.PICK_ACTION;
        this.action = null;
    }

    /** Step G: the summary of the chosen action. */
    public void chooseAction(FieldAction action) {
        requireStep(SessionStep.PICK_ACTION);
        this.action = Objects.requireNonNull(action);
        this.step = SessionStep.CONFIRM;
    }

    private void requireStep(SessionStep expected) {
        if (step != expected) {
            throw new IllegalStateException("Session is at " + step + ", not " + expected);
        }
    }
}
