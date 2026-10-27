alter table organization_invitation add column project_id uuid;
alter table if exists organization_invitation add constraint fk_invitation_project foreign key (project_id) references project;
