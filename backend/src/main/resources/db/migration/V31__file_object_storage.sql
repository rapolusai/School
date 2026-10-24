-- Uploaded files can live in S3 (akshara.files.store = s3, see docs/adr/0004-file-storage.md). The row stays the
-- file's record (owner, name, type, size, hash, school, row-level security); only the bytes move. storage says
-- where they are: DATABASE (in "bytes", as before) or S3 (under "<tenant_id>/<id>" in the files bucket, "bytes"
-- empty). Existing files stay in the database until they are copied (FILES_COPY_TO_S3).

alter table files.stored_file add column storage varchar(10) not null default 'DATABASE';
alter table files.stored_file alter column bytes drop not null;

alter table files.stored_file drop constraint ck_stored_file_size;
alter table files.stored_file add constraint ck_stored_file_size check (size_bytes between 1 and 5242880);
alter table files.stored_file add constraint ck_stored_file_storage check (
    (storage = 'DATABASE' and bytes is not null and octet_length(bytes) = size_bytes)
    or (storage = 'S3' and bytes is null));

-- The copy to S3 empties "bytes" and sets storage on rows that already exist.
grant update (storage, bytes) on files.stored_file to ${appRole};
