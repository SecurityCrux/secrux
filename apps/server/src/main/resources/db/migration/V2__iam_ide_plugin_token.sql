-- IDE plugin access tokens (used by IntelliJ plugin and future IDE integrations)
CREATE TABLE IF NOT EXISTS iam_ide_plugin_token (
    token_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    token_hash TEXT NOT NULL,
    token_hint TEXT NOT NULL,
    name TEXT,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (token_hash)
);

CREATE INDEX IF NOT EXISTS idx_iam_ide_plugin_token_user ON iam_ide_plugin_token(user_id);
CREATE INDEX IF NOT EXISTS idx_iam_ide_plugin_token_tenant ON iam_ide_plugin_token(tenant_id);
CREATE INDEX IF NOT EXISTS idx_iam_ide_plugin_token_created ON iam_ide_plugin_token(tenant_id, created_at DESC);
