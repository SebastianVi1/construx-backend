package org.andre.construx.modules.project.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.Organization;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity ()
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "project")
@Getter
public class Project {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @OneToMany(mappedBy = "project")
    private Set<ProjectAccess> projectAccesses = new HashSet<>();






}
