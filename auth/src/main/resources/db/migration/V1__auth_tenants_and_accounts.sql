CREATE SCHEMA auth;

CREATE TABLE auth.tenants (
    id UUID PRIMARY KEY,
    slug VARCHAR(63) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT tenants_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE auth.accounts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES auth.tenants(id),
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT accounts_tenant_email_unique UNIQUE (tenant_id, email),
    CONSTRAINT accounts_email_normalized CHECK (
        email = lower(btrim(email)) AND email <> '' AND position('@' in email) > 1
    ),
    CONSTRAINT accounts_password_hash_nonempty CHECK (length(btrim(password_hash)) > 0),
    CONSTRAINT accounts_role_valid CHECK (role IN ('ADMIN', 'MEMBER'))
);
