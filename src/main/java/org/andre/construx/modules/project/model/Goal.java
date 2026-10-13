package org.andre.construx.modules.project.model;

import org.andre.construx.shared.validation.DomainValidation;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
    private Instant dueAt;

    @OneToMany(mappedBy = "goal")
    private Set<Activity> activities = new HashSet<>();

    public Goal(Project project, String name, Instant dueAt) {
        this.project = DomainValidation.required(project, "Project is required");
        if (name == null || name.isBlank() || name.strip().length() > 160) {
            throw new IllegalArgumentException("Goal name must have 1 to 160 characters");
        }
        this.name = name.strip();
        this.dueAt = DomainValidation.required(dueAt, "Goal deadline is required");
    }

    public boolean isCompleted() {
        return !activities.isEmpty() && activities.stream()
                .allMatch(activity -> activity.getStatus() == ActivityStatus.DONE);
    }

    void addActivity(Activity activity) {
        activities.add(activity);
    }
}
