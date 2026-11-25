-- Photos a Linked number sent during a Field update, before YES (Field updates contract §2, §5 step F). The bytes are
-- in private storage under whatsapp/pending/{wa_id}/; YES hands the keys to a Progress note and drops these rows, any
-- other end of the conversation deletes the rows and the stored objects. position is the order they arrived (0-4).
create table whatsapp_pending_photo (
    id uuid primary key,
    wa_id varchar(20) not null,
    storage_key varchar(300) not null,
    content_type varchar(50) not null,
    size_bytes bigint not null,
    position int not null,
    constraint uq_whatsapp_pending_photo_key unique (storage_key),
    constraint uq_whatsapp_pending_photo_position unique (wa_id, position),
    constraint ck_whatsapp_pending_photo_position check (position between 0 and 4),
    constraint fk_whatsapp_pending_photo_session foreign key (wa_id) references whatsapp_session (wa_id)
        on delete cascade
);
