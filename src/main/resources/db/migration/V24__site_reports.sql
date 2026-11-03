CREATE TABLE site_report (
    id              UUID         PRIMARY KEY,
    project_id      UUID         NOT NULL REFERENCES project(id),
    title           VARCHAR(200) NOT NULL,
    generated_by    UUID         REFERENCES organization_membership(id),
    storage_key     VARCHAR(300) NOT NULL UNIQUE,
    size_bytes      BIGINT,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP(6) WITH TIME ZONE  NOT NULL,
    completed_at    TIMESTAMP(6) WITH TIME ZONE
);

CREATE INDEX site_report_project_idx ON site_report(project_id, created_at DESC);
