-- 미국 자동매매 추천 구성 적용 전 운영 DB에서 1회 실행합니다.
ALTER TABLE kiwoom_us_strategy_settings
  ADD COLUMN fundamental_filter_enabled BIT(1) NOT NULL DEFAULT b'1',
  ADD COLUMN max_forward_pe DOUBLE NOT NULL DEFAULT 50,
  ADD COLUMN min_roe_percent DOUBLE NOT NULL DEFAULT 10;

UPDATE kiwoom_us_strategy_settings
SET max_order_percent = 10,
    max_positions = 3,
    daily_max_buys = 2,
    min_change_percent = 2,
    max_change_percent = 8,
    min_volume_ratio = 1.2,
    fundamental_filter_enabled = b'1',
    max_forward_pe = 50,
    min_roe_percent = 10,
    max_spread_percent = 0.15,
    stop_loss_percent = 3,
    take_profit_percent = 5,
    take_profit_percent2 = 8,
    max_holding_days = 5,
    symbol_cooldown_days = 5,
    daily_loss_limit_percent = 3
WHERE id = 1;
