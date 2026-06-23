package com.hyunchang.webapp.entity;

public enum PortfolioAssetType {
    STOCK,
    CASH;

    public static PortfolioAssetType from(String value) {
        if (value == null || value.isBlank()) {
            return STOCK;
        }
        return PortfolioAssetType.valueOf(value.trim().toUpperCase());
    }
}
