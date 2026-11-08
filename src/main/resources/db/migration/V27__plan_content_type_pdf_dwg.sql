-- Allow PDF and DWG plans in addition to the original image types.

alter table project_plan
    drop constraint if exists project_plan_content_type_check;

alter table project_plan
    add constraint project_plan_content_type_check
        check (content_type in (
            'image/jpeg', 'image/png', 'image/webp',
            'application/pdf',
            'application/acad', 'application/x-dwg', 'image/vnd.dwg'
        ));
