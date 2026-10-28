CREATE TABLE account (
    account_number VARCHAR(10) PRIMARY KEY,
    document VARCHAR(14) NOT NULL,
    balance NUMERIC(19, 2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_account_document UNIQUE (document)
);

CREATE TABLE financial_transaction (
    id UUID PRIMARY KEY,
    transaction_type VARCHAR(20) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_financial_transaction_type CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    CONSTRAINT ck_financial_transaction_amount_positive CHECK (amount > 0)
);

CREATE TABLE account_transaction (
    id UUID PRIMARY KEY,
    account_number VARCHAR(10) NOT NULL,
    financial_transaction_id UUID NOT NULL,
    movement_type VARCHAR(10) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    balance_before NUMERIC(19, 2) NOT NULL,
    balance_after NUMERIC(19, 2) NOT NULL,
    description VARCHAR(500),
    posted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_account_transaction_account FOREIGN KEY (account_number)
        REFERENCES account (account_number),
    CONSTRAINT fk_account_transaction_financial_transaction FOREIGN KEY (financial_transaction_id)
        REFERENCES financial_transaction (id),
    CONSTRAINT ck_account_transaction_type CHECK (movement_type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_account_transaction_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_account_transaction_balance_transition CHECK (
        (movement_type = 'CREDIT' AND balance_after = balance_before + amount)
        OR (movement_type = 'DEBIT' AND balance_after = balance_before - amount)
    )
);

CREATE INDEX ix_account_transaction_account_posted_at
    ON account_transaction (account_number, posted_at, id);

CREATE TABLE idempotency_record (
    id UUID PRIMARY KEY,
    scope VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    financial_transaction_id UUID,
    response_status INTEGER NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_idempotency_record_key UNIQUE (idempotency_key),
    CONSTRAINT fk_idempotency_record_financial_transaction FOREIGN KEY (financial_transaction_id)
        REFERENCES financial_transaction (id),
    CONSTRAINT ck_idempotency_record_response_status CHECK (response_status BETWEEN 200 AND 599)
);
