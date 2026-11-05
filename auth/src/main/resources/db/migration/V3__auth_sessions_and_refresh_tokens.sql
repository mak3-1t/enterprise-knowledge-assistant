ALTER TABLE auth.accounts ADD CONSTRAINT accounts_identity_tenant_unique UNIQUE (id, tenant_id);

CREATE TABLE auth.sessions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES auth.tenants(id),
    account_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT sessions_account_tenant_fk FOREIGN KEY (account_id, tenant_id)
        REFERENCES auth.accounts(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT sessions_expiry_valid CHECK (expires_at > created_at)
);
CREATE INDEX sessions_account_idx ON auth.sessions(account_id, tenant_id);

CREATE TABLE auth.refresh_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES auth.sessions(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT refresh_hash_valid CHECK (token_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX refresh_session_idx ON auth.refresh_tokens(session_id);
