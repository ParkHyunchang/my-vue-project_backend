-- 원화 고정 일일 손실 한도를 손실률 기준으로 전환합니다.
-- 기존 daily_loss_limit_amount 값은 보존하며, 새 손실률은 안전을 위해 0%(비활성)로 시작합니다.
ALTER TABLE kiwoom_strategy_settings
    ADD COLUMN daily_loss_limit_percent DOUBLE NOT NULL DEFAULT 0;

-- 당일 첫 점검 이후의 실제 입출금만 기준자산에 반영하기 위한 스냅샷입니다.
ALTER TABLE kiwoom_strategy_control_state
    ADD COLUMN daily_loss_base_net_cash_flow BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN daily_loss_net_cash_flow BIGINT NOT NULL DEFAULT 0;
