package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.KiwoomUsRiskResponse;
import com.hyunchang.webapp.entity.KiwoomUsAccountRisk;
import com.hyunchang.webapp.entity.KiwoomUsCapitalFlow;
import com.hyunchang.webapp.repository.KiwoomUsAccountRiskRepository;
import com.hyunchang.webapp.repository.KiwoomUsCapitalFlowRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KiwoomUsAccountRiskService {
    private final KiwoomUsAccountRiskRepository repository;
    private final KiwoomUsCapitalFlowRepository flows;

    public KiwoomUsAccountRiskService(
            KiwoomUsAccountRiskRepository repository, KiwoomUsCapitalFlowRepository flows) {
        this.repository = repository;
        this.flows = flows;
    }

    private KiwoomUsAccountRisk current() {
        return repository.findById(1L).orElseGet(KiwoomUsAccountRisk::new);
    }

    public KiwoomUsRiskResponse status() {
        return response(current());
    }

    @Transactional
    public synchronized KiwoomUsRiskResponse activate(
            BigDecimal equity, boolean allHoldings, boolean flowsConfirmed) {
        var state = current();
        if (state.isEnabled()) throw new IllegalStateException("이미 활성화된 정책의 고점은 초기화할 수 없습니다.");
        if (!allHoldings || !flowsConfirmed)
            throw new IllegalArgumentException("전체 보유 관리와 입출금 확인에 동의해야 합니다.");
        requirePositive(equity);
        state.setEnabled(true);
        state.setUnits(equity);
        state.setPeakNav(BigDecimal.ONE);
        state.setEquityUsd(equity);
        state.setObservedAt(LocalDateTime.now());
        repository.saveAndFlush(state);
        return response(state);
    }

    @Transactional
    public synchronized KiwoomUsRiskResponse observe(BigDecimal equity) {
        var state = current();
        if (!state.isEnabled()) return response(state);
        requireEquity(equity);
        state.setEquityUsd(equity);
        state.setObservedAt(LocalDateTime.now());
        if (!state.isCapitalFlowReview()) evaluate(state, equity);
        repository.saveAndFlush(state);
        return response(state);
    }

    @Transactional
    public synchronized KiwoomUsRiskResponse requireFlowReview(boolean required) {
        if (!required) throw new IllegalArgumentException("입출금 원장 확인 없이 검토 상태를 해제할 수 없습니다.");
        var state = current();
        if (!state.isEnabled()) throw new IllegalStateException("정책 활성화가 필요합니다.");
        state.setCapitalFlowReview(true);
        repository.saveAndFlush(state);
        return response(state);
    }

    @Transactional
    public synchronized KiwoomUsRiskResponse recordFlow(
            String reference, BigDecimal amount, boolean confirmed, BigDecimal currentEquity) {
        if (!confirmed
                || reference == null
                || reference.isBlank()
                || reference.length() > 100
                || amount == null
                || amount.scale() > 8)
            throw new IllegalArgumentException("고유 입출금 참조번호, USD 금액 및 확인이 필요합니다.");
        reference = reference.trim();
        var state = current();
        var existing = flows.findById(reference);
        if (existing.isPresent()) {
            if (existing.get().getAmountUsd().compareTo(amount) != 0)
                throw new IllegalArgumentException("동일 참조번호의 금액을 변경할 수 없습니다.");
            return response(state);
        }
        if (!state.isEnabled() || !state.isCapitalFlowReview())
            throw new IllegalStateException("자금 이동 전 입출금 검토를 먼저 시작하세요.");
        requireEquity(currentEquity);
        BigDecimal before = currentEquity.subtract(amount);
        requirePositive(before);
        requirePositive(state.getUnits());
        evaluate(state, before); // A deposit cannot erase an existing drawdown.
        BigDecimal nav = before.divide(state.getUnits(), 12, RoundingMode.HALF_UP);
        BigDecimal units = state.getUnits().add(amount.divide(nav, 12, RoundingMode.HALF_UP));
        requirePositive(units);
        state.setUnits(units);
        state.setEquityUsd(currentEquity);
        state.setCapitalFlowReview(false);
        state.setObservedAt(LocalDateTime.now());
        flows.saveAndFlush(new KiwoomUsCapitalFlow(reference, amount));
        repository.saveAndFlush(state);
        return response(state);
    }

    public void requireBuyAllowed() {
        var s = current();
        if (!s.isEnabled()
                || s.isCapitalFlowReview()
                || s.isTriggered()
                || s.getObservedAt() == null
                || s.getObservedAt().isBefore(LocalDateTime.now().minusSeconds(90)))
            throw new IllegalStateException(
                    "전체계좌 10% 위험정책 활성화, 최신 계좌 및 입출금 확인이 필요합니다. 발동된 정책은 재시작으로 해제되지 않습니다.");
    }

    @Transactional
    public synchronized void liquidationComplete(boolean complete) {
        var s = current();
        if (!s.isTriggered()) return;
        s.setLiquidationPending(!complete);
        repository.saveAndFlush(s);
    }

    private void evaluate(KiwoomUsAccountRisk s, BigDecimal equity) {
        requirePositive(s.getUnits());
        BigDecimal nav = equity.divide(s.getUnits(), 12, RoundingMode.HALF_UP);
        s.setPeakNav(s.getPeakNav().max(nav));
        if (nav.compareTo(s.getPeakNav().multiply(new BigDecimal("0.90"))) <= 0) {
            s.setTriggered(true);
            s.setLiquidationPending(true);
        }
    }

    private void requireEquity(BigDecimal n) {
        if (n == null || n.signum() < 0) throw new IllegalStateException("전체 계좌 평가액을 확인할 수 없습니다.");
    }

    private void requirePositive(BigDecimal n) {
        if (n == null || n.signum() <= 0)
            throw new IllegalStateException("양수 계좌 평가액/기준 수량이 필요합니다.");
    }

    private KiwoomUsRiskResponse response(KiwoomUsAccountRisk s) {
        BigDecimal peak = s.getPeakNav().multiply(s.getUnits());
        BigDecimal drawdown =
                peak.signum() > 0
                        ? peak.subtract(s.getEquityUsd())
                                .multiply(BigDecimal.valueOf(100))
                                .divide(peak, 6, RoundingMode.HALF_UP)
                                .max(BigDecimal.ZERO)
                        : BigDecimal.ZERO;
        String message =
                !s.isEnabled()
                        ? "전체 보유 매도 관리 정책이 아직 비활성입니다."
                        : s.isTriggered()
                                ? "고점 대비 10% 하락: 신규 매수 금지, 미체결 매수 취소 및 전체 보유 청산 대기. 10% 손실 보장은 아닙니다."
                                : s.isCapitalFlowReview()
                                        ? "입출금 검토 중: 신규 매수와 새로운 낙폭 판정을 보류합니다."
                                        : "입출금은 변경 전에 검토를 시작하고 변경 후 원장에 등록해야 합니다. 자동 감지하지 않습니다.";
        return new KiwoomUsRiskResponse(
                s.isEnabled(),
                s.getEquityUsd(),
                peak,
                drawdown,
                10,
                s.isTriggered(),
                s.isLiquidationPending(),
                s.isEnabled() && !s.isCapitalFlowReview(),
                s.getObservedAt() == null
                        ? null
                        : s.getObservedAt()
                                .atZone(java.time.ZoneId.systemDefault())
                                .toOffsetDateTime(),
                message);
    }
}
