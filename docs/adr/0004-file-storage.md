# 4. Uploaded files live in PostgreSQL for the pilot, behind a FileStore interface

Status: accepted (Phase 1, timetable and homework)

## Context
Homework needs attachments (worksheets from teachers) and submissions (photos or documents from
students). Later modules will need the same: admission documents, leave letters, fee receipts.
The pilot runs a few schools on one server, with no AWS and no other storage service. Files are
school data and must stay as private as the rows that point at them.

## Decision
- One module, `com.akshara.files`, owns uploaded files. Other modules use only its public types:
  `FileStore` (put, info, content, list, delete), `FileUploads` (checks a multipart part),
  `FileOwner` (module, type and id of the record a file belongs to) and `FileAccessPolicy`.
- The pilot implementation, `DatabaseFileStore`, keeps the bytes in `files.stored_file` (`bytea`)
  with the file's name, type, size, SHA-256 and owner. The table has `tenant_id` and the same
  `tenant_isolation` row-level security policy as every school table, so another school's file is
  invisible even to a buggy query.
- Every upload is checked before it is stored, by `FileUploads`:
  - at most 5 MB per file (Spring's multipart limits are set to match, and a larger request
    answers `413` with `errors.file`);
  - only PDF, JPEG, PNG, WEBP, DOCX, XLSX, PPTX and TXT, recognised from the first bytes of the
    contents (`FileSniffer`), never from the browser's content type; the extension must agree
    with the contents, so a PNG named `.pdf` or an `.html` page is refused with `400`;
  - names are cleaned: no folders, control characters or `<>:"/\|?*`, at most 100 characters before
    the extension, which is always one of the detected type's.
- Downloads go through one endpoint, `GET /api/files/{id}`. The module that owns the file decides
  who may read it, through its `FileAccessPolicy` (homework: staff who can see the homework, the
  students of its sections and their parents; a submission: staff, that student and their
  parents). Anyone else, and any id of another school, gets `404`. Responses are always
  `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff` and `Cache-Control:
  private, no-store`, so a file is never rendered as a page of the app.
- The owning module deletes its files with the record (`deleteAll(owner)`).

### Reusing the multipart pattern
A module that accepts files:
1. Takes `@RequestPart("file") MultipartFile` (one file) or `@RequestParam("files")
   List<MultipartFile>` on a `consumes = multipart/form-data` endpoint.
2. Calls `FileUploads.read(part, "<field>")`, which returns an `IncomingFile` or throws the
   `400`/`413` problem with `errors.<field>`.
3. Stores it with `fileStore.put(new FileOwner("<module>", "<type>", recordId), file, userId)`
   inside its own `@Transactional` method, after its own permission checks, and writes an audit
   event with the file id and name (never the contents).
4. Registers a `FileAccessPolicy` bean whose `ownerModule()` is `<module>`.

## Consequences
- Backups, restores, tenant isolation and deletion cover files with no extra system to run.
- Rows are at most 5 MB and are read only for a download, so the database stays fast at pilot
  scale (tens of thousands of files). It does grow the database and its backups.
- Moving to object storage (for example S3 with server-side encryption) means a second
  `FileStore` implementation that writes the bytes to a bucket under `<tenant>/<file id>` and
  keeps the same metadata row (without `bytes`). The API, the checks, `FileAccessPolicy` and the
  download headers stay the same, so no endpoint or client changes; a one-off job copies the
  existing rows. Downloads could then use short-lived signed URLs.
- There is no virus scanning yet. Files are only ever served as downloads with `nosniff`, and only
  the allowed types are accepted, which limits the risk until a scanner is added.
