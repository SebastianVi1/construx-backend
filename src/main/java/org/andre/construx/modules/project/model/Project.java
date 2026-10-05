package org.andre.construx.modules.project.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.Organization;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "project")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @NotBlank @Size(max = 160)
    @Column(nullable = false, length = 160)
    private String name;

    @OneToMany(mappedBy = "project")
    private Set<ProjectAccess> accesses = new HashSet<>();

    @OneToMany(mappedBy = "project")
    private Set<Goal> goals = new HashSet<>();

    public Project(Organization organization, String name) {
        this.organization = Objects.requireNonNull(organization, "Organization is required");
        rename(name);
    }

    public void rename(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 160) {
            throw new IllegalArgumentException("Project name must have 1 to 160 characters");
        }
        this.name = name.strip();
    }

    public boolean sameAs(Project other) {
        return this == other || (other != null && id != null && id.equals(other.getId()));
    }
}
