-- Apply once before deploying the trend lifecycle release. Existing trading mode is preserved.
-- Do not re-run the earlier execution_and_signals migration.
ALTER TABLE kiwoom_us_strategy_settings
  ADD COLUMN trailing_stop_atr_multiplier DOUBLE NOT NULL DEFAULT 2,
  ADD COLUMN trailing_activation_r DOUBLE NOT NULL DEFAULT 1,
  ADD COLUMN max_holding_trading_days INT NOT NULL DEFAULT 5;

ALTER TABLE kiwoom_us_trade_proposals
  ADD COLUMN trend_entry_atr DOUBLE NULL,
  ADD COLUMN trend_risk_per_share DOUBLE NULL,
  ADD COLUMN trend_trail_atr_multiplier DOUBLE NULL,
  ADD COLUMN trend_trail_activation_r DOUBLE NULL,
  ADD COLUMN trend_max_holding_trading_days INT NULL,
  ADD COLUMN first_filled_at DATETIME(6) NULL;

ALTER TABLE kiwoom_us_account_holdings
  ADD COLUMN trend_entry_atr DOUBLE NULL,
  ADD COLUMN trend_risk_per_share DOUBLE NULL,
  ADD COLUMN trend_trail_atr_multiplier DOUBLE NULL,
  ADD COLUMN trend_trail_activation_r DOUBLE NULL,
  ADD COLUMN trend_max_holding_trading_days INT NULL,
  ADD COLUMN trend_entry_proposal_id BIGINT NULL,
  ADD COLUMN trend_started_on DATE NULL,
  ADD COLUMN trend_high_water_price DECIMAL(19,4) NULL,
  ADD COLUMN trend_stop_price DECIMAL(19,4) NULL;

-- No automatic mode switch and no retrofit of existing positions.
-- Select TREND in the strategy popup and save after reviewing entry/exit/risk settings.
SELECT signal_mode, trailing_stop_atr_multiplier, trailing_activation_r,
       max_holding_trading_days FROM kiwoom_us_strategy_settings WHERE id = 1;
