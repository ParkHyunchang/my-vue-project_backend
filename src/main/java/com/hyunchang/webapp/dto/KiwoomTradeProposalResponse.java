package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 전략 판단 이력에 딸린 주문 제안 응답. Entity를 그대로 내보내면 brokerResponse(브로커 원문 JSON) 같은 내부 필드까지 노출되므로 화면이 쓰는 필드만
 * 추린다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KiwoomTradeProposalResponse {
    private Long id;
    private String action; // BUY | SELL | HOLD
    private String stockCode;
    private String stockName;
    private int quantity;
    private String orderType; // LIMIT | MARKET
    private Long limitPrice;
    private int confidence;
    private String reason;
    private String guardFlags; // 쉼표로 구분된 안전 규칙 위반 플래그
    private String status;
    private String rejectionReason;
    private String errorMessage;
    private String brokerOrderNo;
    private int filledQuantity;
    private int remainingQuantity;
    private Long averageFillPrice;
    private LocalDateTime createdAt;

    public static KiwoomTradeProposalResponse from(KiwoomTradeProposal proposal) {
        return KiwoomTradeProposalResponse.builder()
                .id(proposal.getId())
                .action(name(proposal.getAction()))
                .stockCode(proposal.getStockCode())
                .stockName(proposal.getStockName())
                .quantity(proposal.getQuantity())
                .orderType(name(proposal.getOrderType()))
                .limitPrice(proposal.getLimitPrice())
                .confidence(proposal.getConfidence())
                .reason(proposal.getReason())
                .guardFlags(proposal.getGuardFlags())
                .status(name(proposal.getStatus()))
                .rejectionReason(proposal.getRejectionReason())
                .errorMessage(proposal.getErrorMessage())
                .brokerOrderNo(proposal.getBrokerOrderNo())
                .filledQuantity(proposal.getFilledQuantity())
                .remainingQuantity(proposal.getRemainingQuantity())
                .averageFillPrice(proposal.getAverageFillPrice())
                .createdAt(proposal.getCreatedAt())
                .build();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
