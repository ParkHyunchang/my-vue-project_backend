-- Apply once before a deployment using ddl-auto=validate. No live trading is enabled here.
-- Back up the affected tables first. Existing thresholds and trading switches are preserved.
ALTER TABLE kiwoom_us_strategy_settings
  ADD COLUMN signal_mode VARCHAR(16) NOT NULL DEFAULT 'OBSERVE',
  ADD COLUMN min_relative_strength_percent DOUBLE NOT NULL DEFAULT 0,
  ADD COLUMN risk_per_trade_percent DOUBLE NOT NULL DEFAULT 0.5,
  ADD COLUMN atr_stop_multiplier DOUBLE NOT NULL DEFAULT 2,
  ADD COLUMN max_entry_extension_atr DOUBLE NOT NULL DEFAULT 1;

ALTER TABLE kiwoom_us_account_holdings
  ADD COLUMN managed_quantity INT NOT NULL DEFAULT 0,
  ADD COLUMN accounted_buy_quantity BIGINT NULL,
  ADD COLUMN accounted_sell_quantity BIGINT NULL,
  ADD COLUMN pending_quantity_reduction BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN planned_stop_loss_percent DOUBLE NULL,
  ADD COLUMN managed_average_price DECIMAL(19,4) NULL,
  ADD COLUMN last_closed_at DATETIME(6) NULL,
  ADD COLUMN first_take_profit_target_quantity INT NOT NULL DEFAULT 0,
  ADD COLUMN first_take_profit_filled_quantity INT NOT NULL DEFAULT 0;

ALTER TABLE kiwoom_us_trade_proposals
  ADD COLUMN planned_stop_loss_percent DOUBLE NULL;

-- Older NAS schemas do not include the partial-fill cancellation state.
-- Preserve existing labels and their order; append the missing label.
ALTER TABLE kiwoom_us_trade_proposals
  MODIFY COLUMN status ENUM(
    'PROPOSED', 'ORDERED', 'PARTIALLY_FILLED', 'FILLED',
    'CANCEL_REQUESTED', 'CANCELED', 'FAILED', 'UNKNOWN',
    'PARTIALLY_FILLED_CANCELED'
  ) NULL DEFAULT NULL;

-- Ownership counters are deliberately initialized by reconciliation from confirmed fills.
-- Do not set every holding to managed: manual holdings must remain outside automatic exits.
