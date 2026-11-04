package org.andre.construx.modules.documents.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.project.model.Project;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_folder")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 120)
    private String nameEn;

    @Column(nullable = false, length = 120)
    private String nameEs;

    @Column(name = "is_standard", nullable = false)
    private boolean standard;

    @Column(length = 80)
    private String requiresPermission;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private Instant createdAt;

    /** Standard (auto-created) folder with bilingual names. */
    public DocumentFolder(Project project, String nameEn, String nameEs,
                          boolean standard, String requiresPermission, int position) {
        this.project = project;
        this.nameEn = nameEn;
        this.nameEs = nameEs;
        this.standard = standard;
        this.requiresPermission = requiresPermission;
        this.position = position;
        this.createdAt = Instant.now();
    }

    /** User-created custom folder. */
    public DocumentFolder(Project project, String nameEn, String nameEs) {
        this(project, nameEn, nameEs, false, null, 100);
    }

    public void rename(String nameEn, String nameEs) {
        if (nameEn == null || nameEn.isBlank()) throw new IllegalArgumentException("English name is required");
        if (nameEs == null || nameEs.isBlank()) throw new IllegalArgumentException("Spanish name is required");
        if (nameEn.strip().length() > 120 || nameEs.strip().length() > 120)
            throw new IllegalArgumentException("Folder name must be at most 120 characters");
        this.nameEn = nameEn.strip();
        this.nameEs = nameEs.strip();
    }
}
