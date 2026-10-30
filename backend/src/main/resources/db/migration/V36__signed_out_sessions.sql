-- Sessions ended by signing out. An access token is stateless and lives up to 15 minutes; it carries its session id
-- (the refresh-token family), and the API refuses a token whose session is listed here. A row is needed only until
-- every access token of the session has expired, so rows are deleted after expires_at.
-- No school data: the session id is a random UUID, so this sits in the platform schema without row-level security.
create table platform.signed_out_session (
    session_id uuid        primary key,
    expires_at timestamptz not null
);
create index ix_signed_out_session_expires on platform.signed_out_session (expires_at);

grant select, insert, update, delete on platform.signed_out_session to ${appRole};
