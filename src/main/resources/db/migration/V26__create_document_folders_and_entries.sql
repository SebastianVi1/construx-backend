CREATE TABLE document_folder (
    id                  UUID         PRIMARY KEY,
    project_id          UUID         NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name_en             VARCHAR(120) NOT NULL,
    name_es             VARCHAR(120) NOT NULL,
    is_standard         BOOLEAN      NOT NULL DEFAULT FALSE,
    requires_permission VARCHAR(80),
    position            INTEGER      NOT NULL DEFAULT 0,
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX document_folder_project_name_en_idx ON document_folder(project_id, name_en);
CREATE INDEX document_folder_project_idx ON document_folder(project_id, position);

CREATE TABLE document_entry (
    id              UUID         PRIMARY KEY,
    folder_id       UUID         NOT NULL REFERENCES document_folder(id) ON DELETE CASCADE,
    project_id      UUID         NOT NULL,
    organization_id UUID         NOT NULL,
    s3_key          TEXT         NOT NULL UNIQUE,
    file_name       VARCHAR(255) NOT NULL,
    file_size       BIGINT,
    file_type       VARCHAR(80),
    source_feature  VARCHAR(40)  NOT NULL DEFAULT 'manual',
    created_by      UUID,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX document_entry_folder_idx ON document_entry(folder_id, created_at DESC);
CREATE INDEX document_entry_project_idx ON document_entry(project_id);
