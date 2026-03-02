-- V1__init_schema.sql
-- PostgreSQL schema for the distributed ledger write store

CREATE TABLE accounts (
    id          VARCHAR(64)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL,
    name        VARCHAR(255) NOT NULL,
    type        VARCHAR(20)  NOT NULL,
    currency    VARCHAR(3)   NOT NULL,
    status      VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
    metadata    JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE INDEX idx_accounts_tenant ON accounts (tenant_id, status);

CREATE TABLE account_state (
    tenant_id           VARCHAR(64) NOT NULL,
    account_id          VARCHAR(64) NOT NULL,
    posted_balance_minor BIGINT     NOT NULL DEFAULT 0,
    version             BIGINT      NOT NULL DEFAULT 0,
    last_tx_id          VARCHAR(64),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, account_id)
);

CREATE TABLE transactions_min (
    tx_id        VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    currency     VARCHAR(3)   NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'POSTED',
    external_ref VARCHAR(255),
    metadata     JSONB,
    PRIMARY KEY (tx_id)
);

CREATE INDEX idx_tx_tenant_time ON transactions_min (tenant_id, occurred_at, tx_id);
CREATE INDEX idx_tx_tenant_extref ON transactions_min (tenant_id, external_ref)
    WHERE external_ref IS NOT NULL;

CREATE TABLE entries (
    id          BIGSERIAL    NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL,
    tx_id       VARCHAR(64)  NOT NULL,
    account_id  VARCHAR(64)  NOT NULL,
    direction   VARCHAR(6)   NOT NULL,
    amount_minor BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE INDEX idx_entries_tx ON entries (tenant_id, tx_id);
CREATE INDEX idx_entries_account ON entries (tenant_id, account_id, created_at, id);

CREATE TABLE idempotency (
    tenant_id    VARCHAR(64)  NOT NULL,
    key          VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64)  NOT NULL,
    tx_id        VARCHAR(64),
    status       VARCHAR(20)  NOT NULL,
    response_code INT,
    response_body TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, key)
);

CREATE TABLE outbox (
    event_id     UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id    VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    PRIMARY KEY (event_id)
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at)
    WHERE published_at IS NULL;
CREATE INDEX idx_outbox_tenant ON outbox (tenant_id, created_at);
