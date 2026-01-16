create table if not exists ai_client_config (
    config_id uuid primary key,
    tenant_id uuid not null,
    name text not null,
    provider text not null,
    base_url text not null,
    api_key text,
    model text not null,
    is_default boolean not null,
    enabled boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz
);

create index if not exists idx_ai_client_config_tenant on ai_client_config (tenant_id);

create table if not exists ai_mcp_config (
    profile_id uuid primary key,
    tenant_id uuid not null,
    name text not null,
    type text not null,
    endpoint text,
    entrypoint text,
    params jsonb not null default '{}'::jsonb,
    enabled boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz
);

create index if not exists idx_ai_mcp_config_tenant on ai_mcp_config (tenant_id);

create table if not exists ai_agent_config (
    agent_id uuid primary key,
    tenant_id uuid not null,
    name text not null,
    kind text not null,
    entrypoint text,
    params jsonb not null default '{}'::jsonb,
    stage_types text[] not null default '{}',
    mcp_profile_id uuid,
    enabled boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz,
    constraint fk_ai_agent_mcp foreign key (mcp_profile_id) references ai_mcp_config(profile_id) on delete set null
);

create index if not exists idx_ai_agent_config_tenant on ai_agent_config (tenant_id);
