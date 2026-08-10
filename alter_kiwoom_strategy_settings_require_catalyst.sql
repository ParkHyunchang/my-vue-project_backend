ALTER TABLE kiwoom_strategy_settings
    ADD COLUMN require_catalyst_for_auto_buy BIT(1) NULL;

UPDATE kiwoom_strategy_settings
SET require_catalyst_for_auto_buy = b'1'
WHERE require_catalyst_for_auto_buy IS NULL;

ALTER TABLE kiwoom_strategy_settings
    MODIFY COLUMN require_catalyst_for_auto_buy BIT(1) NOT NULL DEFAULT b'1';
