-- An APAAR ID is a national student ID: one school cannot record the same one for two students.
--
-- Existing duplicates are resolved before the index is made: the record that had the ID first (earliest created,
-- then lowest id) keeps it; on the others the ID is cleared and unlocked, so the grid shows it as missing and the
-- office can enter the right one. Each cleared value is kept in the school's audit log
-- (govreport_fields.apaar_duplicate_cleared) so nothing is lost.

with ranked as (
    select id, tenant_id, student_id, apaar_id,
           row_number() over (partition by tenant_id, apaar_id order by created_at, id) as n
      from govreport.student_record
     where apaar_id is not null
), cleared as (
    update govreport.student_record r
       set apaar_id = null, apaar_locked = false, updated_at = now(), version = r.version + 1
      from ranked d
     where d.id = r.id and d.n > 1
    returning r.id, r.tenant_id, r.student_id, d.apaar_id
)
insert into audit.audit_event (id, tenant_id, at, actor_id, actor_name, action, entity_type, entity_id, details)
select gen_random_uuid(), c.tenant_id, now(), null, 'System (migration V31)',
       'govreport_fields.apaar_duplicate_cleared', 'govreport_fields', c.id::text,
       jsonb_build_object('studentId', c.student_id::text, 'apaarId', c.apaar_id,
           'reason', 'The same APAAR ID was recorded for another student of the school.')
  from cleared c;

create unique index uq_student_record_apaar on govreport.student_record (tenant_id, apaar_id)
    where apaar_id is not null;
