package com.hyunchang.webapp.service.kiwoom;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Display calculations preserve missing values; trading calculations remain separate. */
public final class KiwoomDisplaySummary {
    private KiwoomDisplaySummary() {}

    public static Map<String, Object> domestic(JsonNode deposit, JsonNode balance) {
        BigDecimal cash = number(deposit, "entr");
        BigDecimal evaluation = number(balance, "tot_evlt_amt", "evlt_amt");
        if (evaluation == null) evaluation = sumRows(balance, true);
        BigDecimal profit = number(balance, "tot_evlt_pl", "evlt_pl_amt");
        if (profit == null) profit = sumRows(balance, false);
        BigDecimal asset = number(balance, "prsm_dpst_aset_amt");
        String source = asset == null ? "예수금 + 보유주식 평가액" : "키움 기준 · 결제 예정금액 포함";
        if (asset == null && cash != null && evaluation != null) asset = cash.add(evaluation);
        BigDecimal rate = null;
        if (evaluation != null && profit != null && evaluation.subtract(profit).signum() > 0)
            rate =
                    profit.multiply(BigDecimal.valueOf(100))
                            .divide(evaluation.subtract(profit), 8, RoundingMode.HALF_UP);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalAsset", asset);
        result.put("totalAssetSource", asset == null ? "확인 불가" : source);
        result.put("deposit", cash);
        result.put("d1Deposit", number(deposit, "d1_entra"));
        result.put("d2Deposit", number(deposit, "d2_entra"));
        result.put("orderAvailable", number(deposit, "ord_alow_amt", "ord_psbl_cash"));
        result.put("profitLoss", profit);
        result.put("stockProfitRate", rate);
        result.put("totalEvaluation", evaluation);
        result.put("capturedAt", Instant.now());
        result.put("fresh", true);
        return result;
    }

    private static BigDecimal sumRows(JsonNode balance, boolean evaluation) {
        JsonNode rows = balance.path("acnt_evlt_remn_indv_tot");
        if (!rows.isArray()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode row : rows) {
            BigDecimal value;
            if (evaluation) {
                BigDecimal price = number(row, "cur_prc", "prpr");
                BigDecimal quantity = number(row, "rmnd_qty");
                if (price == null || quantity == null || quantity.signum() < 0) return null;
                if (price.signum() == 0 && quantity.signum() > 0) return null;
                value = price.abs().multiply(quantity);
            } else value = number(row, "evltv_prft", "evlt_pl_amt", "pl_amt");
            if (value == null) return null;
            sum = sum.add(value);
        }
        return sum;
    }

    private static BigDecimal number(JsonNode node, String... names) {
        for (String name : names) {
            if (node == null || !node.hasNonNull(name)) continue;
            try {
                return new BigDecimal(node.get(name).asText().replace(",", "").trim());
            } catch (NumberFormatException ignored) {
                /* unknown */
            }
        }
        return null;
    }
}
