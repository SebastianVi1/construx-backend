-- Expense from the Catalog and the equipment rate link (docs/pricing.md §1.12, §6.5, §6.6).
-- An expense from the Catalog freezes its Resource, quantity and unit cost; all three or none.

alter table expense add column resource_id uuid;
alter table expense add column quantity numeric(18,6);
alter table expense add column unit_cost numeric(14,2);
alter table expense add constraint fk_expense_resource foreign key (resource_id) references resource (id);
alter table expense add constraint ck_expense_catalog check (
    ((resource_id is null) = (quantity is null)) and ((resource_id is null) = (unit_cost is null)));
create index ix_expense_resource on expense (resource_id);

alter table equipment add column equipment_rate_resource_id uuid;
alter table equipment add constraint fk_equipment_rate_resource
    foreign key (equipment_rate_resource_id) references resource (id);
