-- 국내 자동매매 보수형 전략 적용 전 운영 DB에서 1회 실행합니다.
ALTER TABLE kiwoom_strategy_settings
  ADD COLUMN swing_max_volume_ratio DOUBLE NOT NULL DEFAULT 5,
  ADD COLUMN min_market_cap_won BIGINT NOT NULL DEFAULT 300000000000,
  ADD COLUMN min_trading_value_won BIGINT NOT NULL DEFAULT 10000000000,
  ADD COLUMN max_spread_percent DOUBLE NOT NULL DEFAULT 0.3,
  ADD COLUMN max_price_above_ma20_percent DOUBLE NOT NULL DEFAULT 10,
  ADD COLUMN max_atr_percent DOUBLE NOT NULL DEFAULT 4,
  ADD COLUMN max_positions INT NOT NULL DEFAULT 3,
  ADD COLUMN max_positions_per_sector INT NOT NULL DEFAULT 1,
  ADD COLUMN stop_loss_cooldown_trading_days INT NOT NULL DEFAULT 5,
  ADD COLUMN daily_stop_loss_limit INT NOT NULL DEFAULT 2;

UPDATE kiwoom_strategy_settings
SET risk_loop_enabled = b'1',
    auto_execute_min_confidence = 90,
    max_buy_deposit_percent = 5,
    swing_min_change_percent = 2,
    swing_max_change_percent = 5,
    swing_min_volume_ratio = 1.5,
    swing_max_volume_ratio = 5,
    min_market_cap_won = 300000000000,
    min_trading_value_won = 10000000000,
    max_spread_percent = 0.3,
    max_price_above_ma20_percent = 10,
    max_atr_percent = 4,
    swing_stop_loss_percent = 3,
    swing_take_profit_percent = 6,
    swing_take_profit_percent2 = 9,
    swing_take_profit_split_percent = 70,
    swing_max_holding_days = 3,
    daily_loss_limit_percent = 1.5,
    daily_max_proposals = 2,
    max_positions = 3,
    max_positions_per_sector = 1,
    stop_loss_cooldown_trading_days = 5,
    daily_stop_loss_limit = 2,
    require_catalyst_for_auto_buy = b'1'
WHERE id = 1;
