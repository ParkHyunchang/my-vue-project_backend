package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 전략 판단 1회(run) 이력 + 그 판단에서 나온 주문 제안 목록. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KiwoomStrategyRunResponse {
    private Long id;
    private String status; // SUCCESS | FAILED | PARSE_FAILED | BLOCKED | SKIPPED
    private String triggeredBy; // MANUAL | SCHEDULE | RISK
    private String marketView;
    private String errorMessage;
    private boolean aiCalled;
    private int inputTokens;
    private int outputTokens;
    private LocalDateTime createdAt;
    private List<KiwoomTradeProposalResponse> proposals;

    public static KiwoomStrategyRunResponse from(
            KiwoomStrategyRun run, List<KiwoomTradeProposalResponse> proposals) {
        return KiwoomStrategyRunResponse.builder()
                .id(run.getId())
                .status(name(run.getStatus()))
                .triggeredBy(name(run.getTriggeredBy()))
                .marketView(run.getMarketView())
                .errorMessage(run.getErrorMessage())
                .aiCalled(run.isAiCalled())
                .inputTokens(run.getInputTokens())
                .outputTokens(run.getOutputTokens())
                .createdAt(run.getCreatedAt())
                .proposals(proposals)
                .build();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
