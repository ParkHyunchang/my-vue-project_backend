ALTER TABLE kiwoom_strategy_control_state
  ADD COLUMN last_close_asset_date DATE NULL,
  ADD COLUMN last_close_asset_amount BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN previous_close_asset_date DATE NULL,
  ADD COLUMN previous_close_asset_amount BIGINT NOT NULL DEFAULT 0;
