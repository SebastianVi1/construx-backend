package org.andre.construx.modules.documents.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Reference to a file stored in object storage. The file is never passed through the API:
 * the client uploads directly to the presigned URL and calls confirm, which creates this row.
 */
@Entity
@Table(name = "document_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentEntry {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "folder_id", nullable = false)
    private DocumentFolder folder;

    @Column(nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String s3Key;

    @Column(nullable = false, length = 255)
    private String fileName;

    private Long fileSize;

    @Column(length = 80)
    private String fileType;

    @Column(nullable = false, length = 40)
    private String sourceFeature;

    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    public DocumentEntry(UUID id, DocumentFolder folder, UUID projectId, UUID organizationId, String s3Key,
                         String fileName, Long fileSize, String fileType, String sourceFeature, UUID createdBy) {
        this.id = id != null ? id : UUID.randomUUID();
        this.folder = folder;
        this.projectId = projectId;
        this.organizationId = organizationId;
        this.s3Key = s3Key;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileType = fileType;
        this.sourceFeature = sourceFeature == null ? "manual" : sourceFeature;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }
}
