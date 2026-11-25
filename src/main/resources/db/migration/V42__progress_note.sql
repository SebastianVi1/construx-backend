-- Progress notes: dated entries on one Task, written in the app or by WhatsApp (contract field-updates.md, section 2).
-- Never edited or deleted on their own; they go only with their Task. Text may be null once photos can carry a note.
create table progress_note (
    id uuid primary key,
    activity_id uuid not null,
    author_membership_id uuid not null,
    text varchar(1000),
    source varchar(10) not null,
    created_at timestamp(6) with time zone not null,
    constraint ck_progress_note_source check (source in ('APP', 'WHATSAPP')),
    constraint fk_progress_note_activity foreign key (activity_id) references activity (id) on delete cascade,
    constraint fk_progress_note_author foreign key (author_membership_id) references organization_membership (id)
);

create index ix_progress_note_activity on progress_note (activity_id, created_at);
