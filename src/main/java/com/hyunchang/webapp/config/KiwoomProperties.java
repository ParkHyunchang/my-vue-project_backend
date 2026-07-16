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
}
