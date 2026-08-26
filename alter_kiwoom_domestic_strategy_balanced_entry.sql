-- 기존 보수형 설정을 장중 후보가 전멸하지 않는 균형형 진입 설정으로 1회 갱신합니다.
-- 자동주문 활성화 여부와 손절·익절·일일 손실 한도는 변경하지 않습니다.
UPDATE kiwoom_strategy_settings
SET auto_execute_min_confidence = 85,
    swing_max_change_percent = 8,
    swing_min_volume_ratio = 1.5,
    swing_max_volume_ratio = 8,
    min_market_cap_won = 200000000000,
    min_trading_value_won = 10000000000
WHERE id = 1
  AND auto_execute_min_confidence = 90
  AND swing_max_change_percent = 5
  AND swing_min_volume_ratio = 1.5
  AND swing_max_volume_ratio = 5
  AND min_market_cap_won = 300000000000
  AND min_trading_value_won = 10000000000;
