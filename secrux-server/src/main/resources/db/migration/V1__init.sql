CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "citext";

CREATE TABLE IF NOT EXISTS tenant (
    tenant_id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    plan TEXT NOT NULL DEFAULT 'standard',
    contact_email TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS app_user (
    user_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    email CITEXT NOT NULL,
    phone CITEXT,
    name TEXT,
    roles TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    UNIQUE (tenant_id, email)
);

CREATE INDEX IF NOT EXISTS idx_user_tenant ON app_user(tenant_id);

CREATE TABLE IF NOT EXISTS project (
    project_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name TEXT NOT NULL,
    code_owners TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    UNIQUE (tenant_id, name)
);

CREATE INDEX IF NOT EXISTS idx_project_tenant_created ON project(tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS repository (
    repo_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    source_mode TEXT NOT NULL CHECK (source_mode IN ('remote','upload','mixed')),
    remote_url TEXT,
    scm_type TEXT CHECK (scm_type IN ('github','gitlab','gerrit','bitbucket','git')),
    default_branch TEXT,
    upload_key TEXT,
    upload_checksum TEXT,
    upload_size BIGINT,
    secret_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    UNIQUE (tenant_id, project_id, remote_url)
);

CREATE INDEX IF NOT EXISTS idx_repo_proj ON repository(project_id);
CREATE INDEX IF NOT EXISTS idx_repo_tenant ON repository(tenant_id);
CREATE INDEX IF NOT EXISTS idx_repo_mode ON repository(source_mode);

CREATE TABLE IF NOT EXISTS task (
    task_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    type TEXT NOT NULL,
    spec JSONB NOT NULL,
    status TEXT NOT NULL,
    owner UUID,
    correlation_id TEXT NOT NULL,
    source_ref_type TEXT NOT NULL,
    source_ref TEXT,
    commit_sha TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    UNIQUE (tenant_id, correlation_id)
);

CREATE INDEX IF NOT EXISTS idx_task_tenant_created ON task(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_task_project ON task(project_id);
CREATE INDEX IF NOT EXISTS idx_task_commit ON task(commit_sha);
CREATE INDEX IF NOT EXISTS idx_task_ref ON task(source_ref_type, source_ref);

CREATE TABLE IF NOT EXISTS stage (
    stage_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    task_id UUID NOT NULL,
    type TEXT NOT NULL,
    spec JSONB NOT NULL,
    status TEXT NOT NULL,
    metrics JSONB NOT NULL DEFAULT '{}'::JSONB,
    signals JSONB NOT NULL DEFAULT '{}'::JSONB,
    artifacts_uri TEXT[] NOT NULL DEFAULT '{}',
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_stage_task ON stage(task_id);
CREATE INDEX IF NOT EXISTS idx_stage_tenant ON stage(tenant_id);

CREATE TABLE IF NOT EXISTS finding (
    finding_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    task_id UUID NOT NULL,
    project_id UUID NOT NULL,
    source_engine TEXT NOT NULL,
    rule_id TEXT,
    location JSONB NOT NULL,
    evidence JSONB,
    severity TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    status TEXT NOT NULL,
    introduced_by TEXT,
    fix_version TEXT,
    exploit_maturity TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_finding_active ON finding(project_id, fingerprint, status)
    WHERE deleted_at IS NULL AND status IN ('OPEN','CONFIRMED');

CREATE INDEX IF NOT EXISTS idx_finding_tenant ON finding(tenant_id);
CREATE INDEX IF NOT EXISTS idx_finding_project_created ON finding(project_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_review (
    review_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    finding_id UUID NOT NULL,
    reviewer TEXT NOT NULL,
    verdict TEXT NOT NULL,
    reason TEXT,
    confidence REAL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_review_finding ON ai_review(finding_id);
CREATE INDEX IF NOT EXISTS idx_review_tenant ON ai_review(tenant_id);

CREATE TABLE IF NOT EXISTS finding_detail (
    detail_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    finding_id UUID NOT NULL,
    detail_kind TEXT NOT NULL CHECK (detail_kind IN ('code','web','supply')),
    payload JSONB NOT NULL,
    code_path TEXT,
    code_line INTEGER,
    url TEXT,
    purl TEXT,
    version TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_finding_detail ON finding_detail(finding_id)
    WHERE detail_kind IN ('code','web','supply');

CREATE INDEX IF NOT EXISTS idx_fd_tenant ON finding_detail(tenant_id);
CREATE INDEX IF NOT EXISTS idx_fd_kind ON finding_detail(detail_kind);
CREATE INDEX IF NOT EXISTS idx_fd_purl ON finding_detail(purl);
CREATE INDEX IF NOT EXISTS idx_fd_url ON finding_detail(url);
CREATE INDEX IF NOT EXISTS idx_fd_payload ON finding_detail USING GIN(payload jsonb_path_ops);

CREATE TABLE IF NOT EXISTS ticket (
    ticket_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    external_key TEXT NOT NULL,
    provider TEXT NOT NULL,
    payload JSONB NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ticket_project ON ticket(project_id);
CREATE INDEX IF NOT EXISTS idx_ticket_tenant ON ticket(tenant_id);

CREATE TABLE IF NOT EXISTS baseline (
    baseline_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('SAST','SCA','DAST','IAC')),
    fingerprints TEXT[] NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_baseline_project ON baseline(project_id);
CREATE INDEX IF NOT EXISTS idx_baseline_tenant ON baseline(tenant_id);

CREATE TABLE IF NOT EXISTS rule (
    rule_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    scope TEXT NOT NULL CHECK (scope IN ('global','tenant','project')),
    key TEXT NOT NULL,
    name TEXT NOT NULL,
    engine TEXT NOT NULL,
    langs TEXT[] NOT NULL DEFAULT '{}',
    severity_default TEXT NOT NULL CHECK (severity_default IN ('CRITICAL','HIGH','MEDIUM','LOW','INFO')),
    tags TEXT[] NOT NULL DEFAULT '{}',
    pattern JSONB NOT NULL,
    docs JSONB,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    hash TEXT NOT NULL,
    signature TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deprecated_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_rule_tenant_key ON rule(tenant_id, key) WHERE deprecated_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_rule_tags ON rule USING GIN(tags);
CREATE INDEX IF NOT EXISTS idx_rule_langs ON rule USING GIN(langs);
CREATE INDEX IF NOT EXISTS idx_rule_engine ON rule(engine);
CREATE INDEX IF NOT EXISTS idx_rule_hash ON rule(hash);

CREATE TABLE IF NOT EXISTS rule_group (
    group_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    key TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_group_tenant_key ON rule_group(tenant_id, key) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS rule_group_member (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    group_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    override_enabled BOOLEAN,
    override_severity TEXT CHECK (override_severity IN ('CRITICAL','HIGH','MEDIUM','LOW','INFO')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rgm_group ON rule_group_member(group_id);
CREATE INDEX IF NOT EXISTS idx_rgm_rule ON rule_group_member(rule_id);
CREATE INDEX IF NOT EXISTS idx_rgm_tenant ON rule_group_member(tenant_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_rgm_unique ON rule_group_member(group_id, rule_id);

CREATE TABLE IF NOT EXISTS ruleset (
    ruleset_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    profile TEXT NOT NULL,
    version TEXT NOT NULL,
    source TEXT NOT NULL,
    langs TEXT[] NOT NULL DEFAULT '{}',
    hash TEXT NOT NULL,
    uri TEXT,
    signature TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ruleset_profile_ver ON ruleset(tenant_id, profile, version);
CREATE INDEX IF NOT EXISTS idx_ruleset_created ON ruleset(tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ruleset_item (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    ruleset_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    engine TEXT NOT NULL,
    severity TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    rule_hash TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ruleset_item_rs ON ruleset_item(ruleset_id);
CREATE INDEX IF NOT EXISTS idx_ruleset_item_rule ON ruleset_item(rule_id);
CREATE INDEX IF NOT EXISTS idx_ruleset_item_tenant ON ruleset_item(tenant_id);

CREATE TABLE IF NOT EXISTS rule_bundle (
    bundle_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    key TEXT NOT NULL,
    engine TEXT NOT NULL,
    version TEXT NOT NULL,
    uri TEXT NOT NULL,
    hash TEXT NOT NULL,
    signature TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_bundle_key_ver ON rule_bundle(tenant_id, key, version);

CREATE TABLE IF NOT EXISTS artifact (
    artifact_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    task_id UUID NOT NULL,
    stage_id UUID NOT NULL,
    uri TEXT NOT NULL,
    kind TEXT NOT NULL,
    checksum TEXT,
    size_bytes BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_artifact_stage ON artifact(stage_id);
CREATE INDEX IF NOT EXISTS idx_artifact_task ON artifact(task_id);

-- ---------------------------------------------------------------------------
-- Squashed migrations (previously V2..V20)
-- NOTE: This repository treats Flyway migrations as dev-friendly and idempotent.
-- If you already have a database initialized with the old migration set, rebuild
-- (drop DB / remove volumes) before running this baseline.
-- ---------------------------------------------------------------------------

-- V2__outbox.sql
CREATE TABLE IF NOT EXISTS outbox_event (
    event_id UUID PRIMARY KEY,
    tenant_id UUID,
    correlation_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox_event(status, created_at);

-- V3__rule_soft_delete.sql
ALTER TABLE ruleset
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- V4__ai_client_config.sql
CREATE TABLE IF NOT EXISTS ai_client_config (
    config_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name TEXT NOT NULL,
    provider TEXT NOT NULL,
    base_url TEXT NOT NULL,
    api_key TEXT,
    model TEXT NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_client_default
    ON ai_client_config(tenant_id)
    WHERE is_default;

-- V5__executor.sql
CREATE TABLE IF NOT EXISTS executor (
    executor_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name TEXT NOT NULL,
    status TEXT NOT NULL,
    labels JSONB NOT NULL DEFAULT '{}'::JSONB,
    cpu_capacity INTEGER NOT NULL,
    memory_capacity_mb INTEGER NOT NULL,
    cpu_usage REAL,
    memory_usage_mb INTEGER,
    last_heartbeat TIMESTAMPTZ,
    quic_token TEXT NOT NULL,
    public_key TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_executor_tenant_status ON executor(tenant_id, status);

-- V6__task_repo_engine.sql
ALTER TABLE task
    ADD COLUMN IF NOT EXISTS repo_id UUID,
    ADD COLUMN IF NOT EXISTS engine TEXT;

CREATE INDEX IF NOT EXISTS idx_task_repo ON task(repo_id);

-- V7__repository_git_metadata.sql
CREATE TABLE IF NOT EXISTS repository_git_metadata (
    repo_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    payload JSONB NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_repo_git_metadata_tenant ON repository_git_metadata(tenant_id);

-- V8__task_executor.sql
ALTER TABLE task
    ADD COLUMN IF NOT EXISTS executor_id UUID;

CREATE INDEX IF NOT EXISTS idx_task_executor ON task(executor_id);

-- V9__task_semgrep_logs.sql
ALTER TABLE task
    ADD COLUMN IF NOT EXISTS semgrep_pro_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS semgrep_token_cipher TEXT,
    ADD COLUMN IF NOT EXISTS semgrep_token_expires_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS task_log_chunk (
    chunk_id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    stream TEXT NOT NULL,
    content TEXT NOT NULL,
    is_last BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_task_log_task FOREIGN KEY (task_id) REFERENCES task(task_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_task_log_task_seq ON task_log_chunk(task_id, sequence);

-- V10__repository_git_auth.sql
ALTER TABLE repository
    ADD COLUMN git_auth_mode TEXT NOT NULL DEFAULT 'none'
        CHECK (git_auth_mode IN ('none','basic','token'));

ALTER TABLE repository
    ADD COLUMN git_auth_cipher TEXT;

-- V11__task_log_stage_level.sql
ALTER TABLE task_log_chunk
    ADD COLUMN IF NOT EXISTS stage_id UUID,
    ADD COLUMN IF NOT EXISTS stage_type TEXT,
    ADD COLUMN IF NOT EXISTS log_level TEXT NOT NULL DEFAULT 'INFO';

ALTER TABLE task_log_chunk
    ADD CONSTRAINT fk_task_log_stage
        FOREIGN KEY (stage_id) REFERENCES stage(stage_id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_task_log_stage ON task_log_chunk(stage_id);

-- V12__ai_review_enhancements.sql
-- AI review enhancements: store review source, job linkage, payload, and status transitions.
-- Keep idempotent for local/dev runs.

ALTER TABLE ai_review ADD COLUMN IF NOT EXISTS review_type TEXT;
ALTER TABLE ai_review ADD COLUMN IF NOT EXISTS reviewer_user_id UUID;
ALTER TABLE ai_review ADD COLUMN IF NOT EXISTS job_id TEXT;
ALTER TABLE ai_review ADD COLUMN IF NOT EXISTS status_before TEXT;
ALTER TABLE ai_review ADD COLUMN IF NOT EXISTS status_after TEXT;
ALTER TABLE ai_review ADD COLUMN IF NOT EXISTS payload JSONB;
ALTER TABLE ai_review ADD COLUMN IF NOT EXISTS applied_at TIMESTAMPTZ;
ALTER TABLE ai_review ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_review_job
    ON ai_review(job_id)
    WHERE deleted_at IS NULL AND job_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ai_review_finding_created
    ON ai_review(finding_id, created_at DESC)
    WHERE deleted_at IS NULL;

-- V13__ticket_draft_and_mapping.sql
-- Ticket enhancements: draft basket + ticket-finding mapping + dedupe key.
-- Keep idempotent for local/dev runs.

ALTER TABLE ticket ADD COLUMN IF NOT EXISTS dedupe_key TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_ticket_dedupe_key
    ON ticket(tenant_id, project_id, provider, dedupe_key)
    WHERE deleted_at IS NULL AND dedupe_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS ticket_finding (
    ticket_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    finding_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (ticket_id, finding_id)
);

CREATE INDEX IF NOT EXISTS idx_ticket_finding_ticket ON ticket_finding(ticket_id);
CREATE INDEX IF NOT EXISTS idx_ticket_finding_finding ON ticket_finding(finding_id);
CREATE INDEX IF NOT EXISTS idx_ticket_finding_tenant ON ticket_finding(tenant_id);

CREATE TABLE IF NOT EXISTS ticket_draft (
    draft_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    project_id UUID,
    provider TEXT,
    title_i18n JSONB,
    description_i18n JSONB,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    last_ai_job_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ticket_draft_current
    ON ticket_draft(tenant_id, user_id)
    WHERE deleted_at IS NULL AND status = 'DRAFT';

CREATE INDEX IF NOT EXISTS idx_ticket_draft_tenant_user ON ticket_draft(tenant_id, user_id);
CREATE INDEX IF NOT EXISTS idx_ticket_draft_project ON ticket_draft(project_id);

CREATE TABLE IF NOT EXISTS ticket_draft_item (
    draft_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    finding_id UUID NOT NULL,
    added_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (draft_id, finding_id)
);

CREATE INDEX IF NOT EXISTS idx_ticket_draft_item_draft ON ticket_draft_item(draft_id);
CREATE INDEX IF NOT EXISTS idx_ticket_draft_item_finding ON ticket_draft_item(finding_id);
CREATE INDEX IF NOT EXISTS idx_ticket_draft_item_tenant ON ticket_draft_item(tenant_id);

-- V14__ticket_provider_config.sql
-- Ticket provider integration config (e.g., Jira Cloud).
-- Keep idempotent for local/dev runs.

CREATE TABLE IF NOT EXISTS ticket_provider_config (
    tenant_id UUID NOT NULL,
    provider TEXT NOT NULL,
    base_url TEXT NOT NULL,
    project_key TEXT NOT NULL,
    email TEXT NOT NULL,
    api_token_cipher TEXT NOT NULL,
    issue_type_names JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_ticket_provider_config_tenant
    ON ticket_provider_config(tenant_id);

-- V15__sca_issue.sql
-- SCA issues (dependency vulnerabilities) stored separately from code findings.
-- Keep idempotent for local/dev runs.

CREATE TABLE IF NOT EXISTS sca_issue (
    issue_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    task_id UUID NOT NULL,
    project_id UUID NOT NULL,
    source_engine TEXT NOT NULL,
    vuln_id TEXT NOT NULL,
    severity TEXT NOT NULL,
    status TEXT NOT NULL,
    package_name TEXT,
    installed_version TEXT,
    fixed_version TEXT,
    primary_url TEXT,
    component_purl TEXT,
    component_name TEXT,
    component_version TEXT,
    introduced_by TEXT,
    location JSONB NOT NULL DEFAULT '{}'::jsonb,
    evidence JSONB,
    issue_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sca_issue_active
    ON sca_issue(tenant_id, task_id, issue_key)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_sca_issue_tenant ON sca_issue(tenant_id);
CREATE INDEX IF NOT EXISTS idx_sca_issue_project_created ON sca_issue(project_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sca_issue_task_created ON sca_issue(task_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sca_issue_purl ON sca_issue(component_purl);

-- V16__sca_issue_upsert_key.sql
-- Align SCA issue upsert key with application ON CONFLICT target.
-- Keep idempotent for local/dev runs.

DROP INDEX IF EXISTS uq_sca_issue_active;

CREATE UNIQUE INDEX IF NOT EXISTS uq_sca_issue_active
    ON sca_issue(tenant_id, task_id, issue_key);

-- V17__ticket_draft_item_support_sca.sql
-- Ticket draft items: support both code findings and SCA issues.
-- Keep idempotent for local/dev runs.

DO $$
BEGIN
    IF to_regclass('public.ticket_draft_item') IS NULL THEN
        CREATE TABLE ticket_draft_item (
            draft_id UUID NOT NULL,
            tenant_id UUID NOT NULL,
            item_type TEXT NOT NULL CHECK (item_type IN ('FINDING','SCA_ISSUE')),
            item_id UUID NOT NULL,
            added_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            PRIMARY KEY (draft_id, item_type, item_id)
        );
    ELSE
        -- Migrate legacy schema (ticket_draft_item.draft_id/tenant_id/finding_id/added_at) into generic item columns.
        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'ticket_draft_item' AND column_name = 'finding_id'
        ) THEN
            ALTER TABLE ticket_draft_item
                ADD COLUMN IF NOT EXISTS item_type TEXT NOT NULL DEFAULT 'FINDING';
            ALTER TABLE ticket_draft_item
                ADD COLUMN IF NOT EXISTS item_id UUID;

            EXECUTE 'UPDATE ticket_draft_item SET item_id = finding_id WHERE item_id IS NULL';
            ALTER TABLE ticket_draft_item ALTER COLUMN item_id SET NOT NULL;

            ALTER TABLE ticket_draft_item DROP CONSTRAINT IF EXISTS ticket_draft_item_pkey;
            ALTER TABLE ticket_draft_item
                ADD CONSTRAINT ticket_draft_item_pkey PRIMARY KEY (draft_id, item_type, item_id);

            DROP INDEX IF EXISTS idx_ticket_draft_item_finding;
            ALTER TABLE ticket_draft_item DROP COLUMN IF EXISTS finding_id;
        END IF;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_ticket_draft_item_draft ON ticket_draft_item(draft_id);
CREATE INDEX IF NOT EXISTS idx_ticket_draft_item_tenant ON ticket_draft_item(tenant_id);
CREATE INDEX IF NOT EXISTS idx_ticket_draft_item_item ON ticket_draft_item(item_type, item_id);

CREATE TABLE IF NOT EXISTS ticket_sca_issue (
    ticket_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    issue_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (ticket_id, issue_id)
);

CREATE INDEX IF NOT EXISTS idx_ticket_sca_issue_ticket ON ticket_sca_issue(ticket_id);
CREATE INDEX IF NOT EXISTS idx_ticket_sca_issue_issue ON ticket_sca_issue(issue_id);
CREATE INDEX IF NOT EXISTS idx_ticket_sca_issue_tenant ON ticket_sca_issue(tenant_id);

-- V18__sca_issue_review.sql
-- SCA issue review records (AI/HUMAN) stored separately from code finding reviews.
-- Keep idempotent for local/dev runs.

CREATE TABLE IF NOT EXISTS sca_issue_review (
    review_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    issue_id UUID NOT NULL,
    review_type TEXT,
    reviewer TEXT NOT NULL,
    reviewer_user_id UUID,
    job_id TEXT,
    verdict TEXT NOT NULL,
    reason TEXT,
    confidence REAL,
    status_before TEXT,
    status_after TEXT,
    payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    applied_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sca_issue_review_job
    ON sca_issue_review(job_id)
    WHERE deleted_at IS NULL AND job_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sca_issue_review_issue_created
    ON sca_issue_review(issue_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_sca_issue_review_tenant
    ON sca_issue_review(tenant_id)
    WHERE deleted_at IS NULL;

-- V19__task_name.sql
ALTER TABLE task
    ADD COLUMN IF NOT EXISTS name TEXT;

CREATE INDEX IF NOT EXISTS idx_task_name ON task(name);

-- V20__iam_rbac.sql
ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS username CITEXT,
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS uq_app_user_tenant_username ON app_user(tenant_id, username)
    WHERE deleted_at IS NULL AND username IS NOT NULL;

CREATE TABLE IF NOT EXISTS iam_role (
    role_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL,
    key TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    UNIQUE (tenant_id, key)
);

CREATE INDEX IF NOT EXISTS idx_iam_role_tenant ON iam_role(tenant_id);
CREATE INDEX IF NOT EXISTS idx_iam_role_created ON iam_role(tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS iam_role_permission (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL,
    role_id UUID NOT NULL,
    permission TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, role_id, permission)
);

CREATE INDEX IF NOT EXISTS idx_iam_role_perm_role ON iam_role_permission(role_id);
CREATE INDEX IF NOT EXISTS idx_iam_role_perm_tenant ON iam_role_permission(tenant_id);

CREATE TABLE IF NOT EXISTS iam_user_role (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, user_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_iam_user_role_user ON iam_user_role(user_id);
CREATE INDEX IF NOT EXISTS idx_iam_user_role_role ON iam_user_role(role_id);
CREATE INDEX IF NOT EXISTS idx_iam_user_role_tenant ON iam_user_role(tenant_id);

CREATE TABLE IF NOT EXISTS iam_local_credential (
    user_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    password_hash TEXT NOT NULL,
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_iam_local_credential_tenant ON iam_local_credential(tenant_id);

CREATE TABLE IF NOT EXISTS iam_refresh_token (
    token_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (token_hash)
);

CREATE INDEX IF NOT EXISTS idx_iam_refresh_token_user ON iam_refresh_token(user_id);
CREATE INDEX IF NOT EXISTS idx_iam_refresh_token_expires ON iam_refresh_token(expires_at);
CREATE INDEX IF NOT EXISTS idx_iam_refresh_token_tenant ON iam_refresh_token(tenant_id);
