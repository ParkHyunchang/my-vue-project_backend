ALTER TABLE kiwoom_strategy_settings
  ADD COLUMN swing_take_profit_percent2 DOUBLE NOT NULL DEFAULT 0,
  ADD COLUMN swing_take_profit_split_percent DOUBLE NOT NULL DEFAULT 50;
