-- Minimal Supabase-compatible harness for running migrations against a plain
-- postgres:16 service container in CI. It only provides what the Messenger Pro
-- migration depends on: auth.users, auth.uid(), the Supabase roles and the
-- Realtime publication.

create role anon nologin;
create role authenticated nologin;
create role service_role nologin;

create schema if not exists auth;

create table if not exists auth.users (
  id uuid primary key,
  email text unique,
  created_at timestamptz not null default now()
);

create or replace function auth.uid()
returns uuid
language sql
stable
as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid;
$$;

create publication supabase_realtime;

grant usage on schema auth to authenticated, service_role;
grant execute on function auth.uid() to authenticated, service_role;
