--liquibase formatted sql
--changeset saamp:019-order-preview-snapshot
CREATE TABLE trading_order_preview_snapshot (
    order_id BIGINT PRIMARY KEY REFERENCES trading_order(id),
    price_as_of TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    reserved_cash NUMERIC(20,6) NOT NULL CHECK (reserved_cash >= 0),
    reserved_metal NUMERIC(20,6) NOT NULL CHECK (reserved_metal >= 0),
    estimated_amount NUMERIC(20,6) NOT NULL CHECK (estimated_amount >= 0)
);
--rollback DROP TABLE trading_order_preview_snapshot;
