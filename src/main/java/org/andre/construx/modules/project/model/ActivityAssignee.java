package org.andre.construx.modules.project.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.shared.validation.DomainValidation;

import java.util.UUID;

/** One person in a task's team; only {@link Activity} creates and changes these. */
@Entity
@Table(name = "activity_assignee", uniqueConstraints =
        @UniqueConstraint(name = "uk_activity_assignee", columnNames = {"activity_id", "access_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivityAssignee {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false)
    private Activity activity;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "access_id", nullable = false)
    private ProjectAccess access;

    @NotNull @Enumerated(EnumType.STRING)
    @Column(name = "task_role", nullable = false, length = 20)
    private TaskRole taskRole;

    ActivityAssignee(Activity activity, ProjectAccess access, TaskRole taskRole) {
        this.activity = DomainValidation.required(activity, "Activity is required");
        this.access = DomainValidation.required(access, "Assignee access is required");
        changeRole(taskRole);
    }

    void changeRole(TaskRole taskRole) {
        this.taskRole = DomainValidation.required(taskRole, "Task role is required");
    }

    public UUID membershipId() {
        return access.getOrganizationMembership().getId();
    }
}
