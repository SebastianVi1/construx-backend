-- Photos of a Progress note (contract field-updates.md, section 2): at most 5 per note, in positions 0 to 4.
-- A note now needs text, photos or both, so its text stays nullable. Photos go with their note.
create table progress_note_photo (
    id uuid primary key,
    progress_note_id uuid not null,
    storage_key varchar(300) not null,
    content_type varchar(50) not null,
    size_bytes bigint not null,
    position int not null,
    constraint uq_progress_note_photo_key unique (storage_key),
    constraint uq_progress_note_photo_position unique (progress_note_id, position),
    constraint ck_progress_note_photo_position check (position between 0 and 4),
    constraint fk_progress_note_photo_note foreign key (progress_note_id) references progress_note (id)
        on delete cascade
);
