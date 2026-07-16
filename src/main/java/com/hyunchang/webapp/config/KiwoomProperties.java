package com.hyunchang.webapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 키움 Open API 접속 정보를 환경 변수에서만 읽는 설정 객체입니다. */
@Component
@ConfigurationProperties(prefix = "kiwoom")
public class KiwoomProperties {
    private String mode = "mock";
    private String appKey;
    private String secretKey;
    private String accountNo;
    private boolean tradeEnabled;
    private long refreshBeforeSeconds = 300;
    private long minRequestIntervalMs = 250;
    private Strategy strategy = new Strategy();

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
        private int swingMaxHoldingDays = 5;

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

        public int getSwingMaxHoldingDays() {
            return swingMaxHoldingDays;
        }

        public void setSwingMaxHoldingDays(int value) {
            swingMaxHoldingDays = Math.max(1, Math.min(30, value));
        }
    }

    public boolean isMock() {
        return !"live".equalsIgnoreCase(mode);
    }

    public String getRestBaseUrl() {
        return isMock() ? "https://mockapi.kiwoom.com" : "https://api.kiwoom.com";
    }

    public String getWebsocketUrl() {
        return (isMock() ? "wss://mockapi.kiwoom.com:10000" : "wss://api.kiwoom.com:10000")
                + "/api/dostk/websocket";
    }

    public boolean isConfigured() {
        return appKey != null && !appKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
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

    public Strategy getStrategy() {
        return strategy;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy == null ? new Strategy() : strategy;
    }
}
