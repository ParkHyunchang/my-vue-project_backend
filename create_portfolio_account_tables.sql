-- TEAM-42: additive schema only; never copies or deletes existing portfolio records.
-- For ddl-auto=validate deployments, apply before deploying the new backend.
-- Existing stock/general_holding/isa_holding/irp_holding tables remain authoritative.
CREATE TABLE IF NOT EXISTS portfolio_account (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_key VARCHAR(50) NOT NULL,
    broker VARCHAR(20) NOT NULL,
    name VARCHAR(80) NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    UNIQUE KEY uq_portfolio_account_owner_key (user_id, account_key),
    CONSTRAINT fk_portfolio_account_user FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS portfolio_account_holding (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    asset_type VARCHAR(10) NOT NULL,
    market VARCHAR(2) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    quantity DECIMAL(24,8) NOT NULL,
    avg_price DECIMAL(24,8),
    core BIT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_portfolio_account_symbol (account_id, market, symbol),
    CONSTRAINT fk_portfolio_holding_account FOREIGN KEY (account_id) REFERENCES portfolio_account(id)
);
