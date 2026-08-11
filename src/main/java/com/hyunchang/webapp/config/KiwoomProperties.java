package com.hyunchang.webapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 키움 Open API 접속 정보를 환경 변수에서만 읽는 설정 객체입니다. */
@Component
@ConfigurationProperties(prefix = "kiwoom")
public class KiwoomProperties {
    private String appKey;
    private String secretKey;
    private String accountNo;
    private boolean tradeEnabled;
    private long refreshBeforeSeconds = 300;
    private long minRequestIntervalMs = 250;
    private int maxConsecutiveApiFailures = 3;
    private Strategy strategy = new Strategy();
    private Us us = new Us();

    public static class Us {
        private boolean tradeEnabled;
        private boolean strategyEnabled = true;
        private double maxOrderPercent = 10.0;
        private int maxPositions = 2;
        private int dailyMaxBuys = 1;
        private double dailyLossLimitPercent = 2.0;
        private int maxConsecutiveApiFailures = 3;

        public boolean isTradeEnabled() {
            return tradeEnabled;
        }

        public void setTradeEnabled(boolean value) {
            tradeEnabled = value;
        }

        public boolean isStrategyEnabled() {
            return strategyEnabled;
        }

        public void setStrategyEnabled(boolean value) {
            strategyEnabled = value;
        }

        public double getMaxOrderPercent() {
            return maxOrderPercent;
        }

        public void setMaxOrderPercent(double value) {
            maxOrderPercent = Math.max(0.1, Math.min(100, value));
        }

        public int getMaxPositions() {
            return maxPositions;
        }

        public void setMaxPositions(int value) {
            maxPositions = Math.max(1, Math.min(20, value));
        }

        public int getDailyMaxBuys() {
            return dailyMaxBuys;
        }

        public void setDailyMaxBuys(int value) {
            dailyMaxBuys = Math.max(1, Math.min(20, value));
        }

        public double getDailyLossLimitPercent() {
            return dailyLossLimitPercent;
        }

        public void setDailyLossLimitPercent(double value) {
            dailyLossLimitPercent = Math.max(0, Math.min(30, value));
        }

        public int getMaxConsecutiveApiFailures() {
            return maxConsecutiveApiFailures;
        }

        public void setMaxConsecutiveApiFailures(int value) {
            maxConsecutiveApiFailures = Math.max(1, Math.min(20, value));
        }
    }

    public static class Strategy {
        private boolean enabled = true;
        private long maxOrderAmount = 500_000;
        private int dailyMaxProposals = 10;
        private int cooldownMinutes = 120;
        private boolean allowMarketOrders;

        // 자동 주문 전송(autoExecute)은 env로 켜지 않는다 — DB 설정(kiwoom_strategy_settings)에서
        // 관리자가 명시적으로만 켤 수 있다. env로 두면 배포 환경변수 하나로 실주문이 시작되는 위험이 있다.
        /** 자동 전송 시 요구되는 AI 신뢰도 초기값 (이후 DB 설정이 우선). */
        private int autoExecuteMinConfidence = 85;

        private double maxBuyDepositPercent = 10.0;
        private double swingStopLossPercent = 3.0;
        private double swingTakeProfitPercent = 6.0;
        private double swingTakeProfitPercent2 = 0;
        private double swingTakeProfitSplitPercent = 50.0;
        private int swingMaxHoldingDays = 5;
        private double maxOrderPriceDeviationPercent = 2.0;
        private double defaultDailyLossPercent = 3.0;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getMaxOrderAmount() {
            return maxOrderAmount;
        }

        public void setMaxOrderAmount(long maxOrderAmount) {
            this.maxOrderAmount = maxOrderAmount;
        }

        public int getDailyMaxProposals() {
            return dailyMaxProposals;
        }

        public void setDailyMaxProposals(int dailyMaxProposals) {
            this.dailyMaxProposals = dailyMaxProposals;
        }

        public int getCooldownMinutes() {
            return cooldownMinutes;
        }

        public void setCooldownMinutes(int cooldownMinutes) {
            this.cooldownMinutes = cooldownMinutes;
        }

        public boolean isAllowMarketOrders() {
            return allowMarketOrders;
        }

        public void setAllowMarketOrders(boolean allowMarketOrders) {
            this.allowMarketOrders = allowMarketOrders;
        }

        public int getAutoExecuteMinConfidence() {
            return autoExecuteMinConfidence;
        }

        public void setAutoExecuteMinConfidence(int autoExecuteMinConfidence) {
            this.autoExecuteMinConfidence = Math.max(0, Math.min(100, autoExecuteMinConfidence));
        }

        public double getMaxBuyDepositPercent() {
            return maxBuyDepositPercent;
        }

        public void setMaxBuyDepositPercent(double value) {
            maxBuyDepositPercent = Math.max(0, Math.min(100, value));
        }

        public double getSwingStopLossPercent() {
            return swingStopLossPercent;
        }

        public void setSwingStopLossPercent(double value) {
            swingStopLossPercent = Math.max(0, Math.min(100, value));
        }

        public double getSwingTakeProfitPercent() {
            return swingTakeProfitPercent;
        }

        public void setSwingTakeProfitPercent(double value) {
            swingTakeProfitPercent = Math.max(0, Math.min(100, value));
        }

        public double getSwingTakeProfitPercent2() {
            return swingTakeProfitPercent2;
        }

        public void setSwingTakeProfitPercent2(double value) {
            swingTakeProfitPercent2 = Math.max(0, Math.min(100, value));
        }

        public double getSwingTakeProfitSplitPercent() {
            return swingTakeProfitSplitPercent;
        }

        public void setSwingTakeProfitSplitPercent(double value) {
            swingTakeProfitSplitPercent = Math.max(1, Math.min(99, value));
        }

        public int getSwingMaxHoldingDays() {
            return swingMaxHoldingDays;
        }

        public void setSwingMaxHoldingDays(int value) {
            swingMaxHoldingDays = Math.max(1, Math.min(30, value));
        }

        public double getMaxOrderPriceDeviationPercent() {
            return maxOrderPriceDeviationPercent;
        }

        public void setMaxOrderPriceDeviationPercent(double value) {
            maxOrderPriceDeviationPercent = Math.max(0, Math.min(30, value));
        }

        public double getDefaultDailyLossPercent() {
            return defaultDailyLossPercent;
        }

        public void setDefaultDailyLossPercent(double value) {
            defaultDailyLossPercent = Math.max(0, Math.min(30, value));
        }
    }

    public String getRestBaseUrl() {
        return "https://api.kiwoom.com";
    }

    public String getWebsocketUrl() {
        return "wss://api.kiwoom.com:10000/api/dostk/websocket";
    }

    public String getUsWebsocketUrl() {
        return "wss://api.kiwoom.com:10000/api/us/websocket";
    }

    public boolean isConfigured() {
        return appKey != null
                && !appKey.isBlank()
                && secretKey != null
                && !secretKey.isBlank()
                && accountNo != null
                && !accountNo.isBlank();
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public void setAccountNo(String accountNo) {
        this.accountNo = accountNo;
    }

    public boolean isTradeEnabled() {
        return tradeEnabled;
    }

    public void setTradeEnabled(boolean tradeEnabled) {
        this.tradeEnabled = tradeEnabled;
    }

    public long getRefreshBeforeSeconds() {
        return refreshBeforeSeconds;
    }

    public void setRefreshBeforeSeconds(long refreshBeforeSeconds) {
        this.refreshBeforeSeconds = refreshBeforeSeconds;
    }

    public long getMinRequestIntervalMs() {
        return minRequestIntervalMs;
    }

    public void setMinRequestIntervalMs(long minRequestIntervalMs) {
        this.minRequestIntervalMs = minRequestIntervalMs;
    }

    public int getMaxConsecutiveApiFailures() {
        return maxConsecutiveApiFailures;
    }

    public void setMaxConsecutiveApiFailures(int value) {
        maxConsecutiveApiFailures = Math.max(1, Math.min(20, value));
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy == null ? new Strategy() : strategy;
    }

    public Us getUs() {
        return us;
    }

    public void setUs(Us value) {
        us = value == null ? new Us() : value;
    }
}
