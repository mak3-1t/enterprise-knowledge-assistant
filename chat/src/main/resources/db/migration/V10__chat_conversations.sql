CREATE SCHEMA chat;

CREATE TABLE chat.conversations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES auth.tenants(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES auth.accounts(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0)
);

CREATE TABLE chat.turns (
    conversation_id UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    question TEXT NOT NULL CHECK (length(question) BETWEEN 1 AND 2000),
    response JSONB NOT NULL CHECK (jsonb_typeof(response) = 'object'),
    PRIMARY KEY (conversation_id, revision)
);
