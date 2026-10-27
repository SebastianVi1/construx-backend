package org.andre.construx.modules.project.model;

import org.andre.construx.shared.validation.DomainValidation;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "goal")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Goal {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @NotBlank @Size(max = 160)
    @Column(nullable = false, length = 160)
    private String name;

    @NotNull
    @Column(nullable = false)
    private Instant startAt;

    @NotNull
    @Column(nullable = false)
    private Instant dueAt;

    @OneToMany(mappedBy = "goal")
    private Set<Activity> activities = new HashSet<>();

    public Goal(Project project, String name, Instant startAt, Instant dueAt) {
        this.project = DomainValidation.required(project, "Project is required");
        rename(name);
        changeDates(startAt, dueAt);
    }

    public void rename(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 160) {
            throw new IllegalArgumentException("Goal name must have 1 to 160 characters");
        }
        this.name = name.strip();
    }

    public void changeStartAt(Instant startAt) {
        changeDates(startAt, dueAt);
    }

    public void changeDueAt(Instant dueAt) {
        changeDates(startAt, dueAt);
    }

    /** A phase cannot cut through the tasks inside it; both dates are checked as a pair. */
    public void changeDates(Instant startAt, Instant dueAt) {
        DomainValidation.required(startAt, "Goal start is required");
        DomainValidation.required(dueAt, "Goal deadline is required");
        if (startAt.isAfter(dueAt)) {
            throw new IllegalArgumentException("Goal cannot start after it ends");
        }
        if (activities.stream().anyMatch(activity -> activity.getStartAt().isBefore(startAt))) {
            throw new IllegalArgumentException("Goal cannot start after its activities");
        }
        if (activities.stream().anyMatch(activity -> activity.getDueAt().isAfter(dueAt))) {
            throw new IllegalArgumentException("Goal cannot end before its activities");
        }
        this.startAt = startAt;
        this.dueAt = dueAt;
    }

    /** Moves the phase and every task inside it by the same offset. */
    public void shift(Duration offset) {
        DomainValidation.required(offset, "Shift is required");
        if (offset.isZero()) {
            throw new IllegalArgumentException("Shift must move the goal");
        }
        startAt = startAt.plus(offset);
        dueAt = dueAt.plus(offset);
        activities.forEach(activity -> activity.shiftBy(offset));
    }

    public boolean isCompleted() {
        return !activities.isEmpty() && activities.stream()
                .allMatch(activity -> activity.getStatus() == ActivityStatus.DONE);
    }

    void addActivity(Activity activity) {
        activities.add(activity);
    }
}
