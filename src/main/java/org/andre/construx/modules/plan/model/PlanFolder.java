package org.andre.construx.modules.plan.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.Organization;

import java.util.UUID;

@Entity
@Table(name = "plan_folder")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanFolder {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private PlanFolder parent;
    @Column(nullable = false, length = 120)
    private String name;

    public PlanFolder(Organization organization, PlanFolder parent, String name) {
        this.organization = organization;
        update(parent, name);
    }

    /** The service checks ancestry under the organization's tree lock. */
    public void update(PlanFolder parent, String name) {
        if (name == null || name.isBlank() || name.strip().length() > 120) {
            throw new IllegalArgumentException("Folder name must have 1 to 120 characters");
        }
        if (parent != null && !parent.organization.getId().equals(organization.getId())) {
            throw new IllegalArgumentException("Folder belongs to another organization");
        }
        this.parent = parent;
        this.name = name.strip();
    }
}
