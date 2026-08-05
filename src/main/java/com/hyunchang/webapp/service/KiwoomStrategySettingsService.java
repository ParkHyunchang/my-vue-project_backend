package com.hyunchang.webapp.service;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomStrategySettings;
import com.hyunchang.webapp.repository.KiwoomStrategySettingsRepository;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KiwoomStrategySettingsService {
    private static final Logger log = LoggerFactory.getLogger(KiwoomStrategySettingsService.class);
    private static final int DEFAULT_CANDIDATE_REEVALUATION_MINUTES = 60;
    private static final double DEFAULT_SWING_MIN_CHANGE_PERCENT = 2.0;
    private static final double DEFAULT_SWING_MAX_CHANGE_PERCENT = 8.0;
    private static final double DEFAULT_SWING_MIN_VOLUME_RATIO = 2.0;
    private final KiwoomStrategySettingsRepository repo;
    private final KiwoomProperties props;
    private final AiPromptService prompts;

    public KiwoomStrategySettingsService(
            KiwoomStrategySettingsRepository repo,
            KiwoomProperties props,
            AiPromptService prompts) {
        this.repo = repo;
        this.props = props;
        this.prompts = prompts;
    }

    // 자동 주문 전송은 항상 꺼진 상태로 시드한다 — 실주문 활성화는 관리자가 설정 API로만 명시적으로 켤 수 있다.
    @PostConstruct
    @Transactional
    public void seed() {
        if (repo.existsById(1L)) {
            KiwoomStrategySettings existing = current();
            boolean changed = false;
            if (existing.getCandidateReevaluationMinutes() <= 0) {
                existing.setCandidateReevaluationMinutes(DEFAULT_CANDIDATE_REEVALUATION_MINUTES);
                changed = true;
            }
            if (existing.getSwingMinChangePercent() <= 0) {
                existing.setSwingMinChangePercent(DEFAULT_SWING_MIN_CHANGE_PERCENT);
                changed = true;
            }
            if (existing.getSwingMaxChangePercent() <= 0) {
                existing.setSwingMaxChangePercent(DEFAULT_SWING_MAX_CHANGE_PERCENT);
                changed = true;
            }
            if (existing.getSwingMaxChangePercent() < existing.getSwingMinChangePercent()) {
                existing.setSwingMaxChangePercent(existing.getSwingMinChangePercent());
                changed = true;
            }
            if (existing.getSwingMinVolumeRatio() <= 0) {
                existing.setSwingMinVolumeRatio(DEFAULT_SWING_MIN_VOLUME_RATIO);
                changed = true;
            }
            if (changed) repo.save(existing);
            return;
        }
        KiwoomProperties.Strategy p = props.getStrategy();
        KiwoomStrategySettings s = new KiwoomStrategySettings();
        s.setAutoExecute(false);
        s.setAutoExecuteMinConfidence(p.getAutoExecuteMinConfidence());
        s.setMaxBuyDepositPercent(p.getMaxBuyDepositPercent());
        s.setCandidateReevaluationMinutes(DEFAULT_CANDIDATE_REEVALUATION_MINUTES);
        s.setSwingMinChangePercent(DEFAULT_SWING_MIN_CHANGE_PERCENT);
        s.setSwingMaxChangePercent(DEFAULT_SWING_MAX_CHANGE_PERCENT);
        s.setSwingMinVolumeRatio(DEFAULT_SWING_MIN_VOLUME_RATIO);
        s.setSwingStopLossPercent(p.getSwingStopLossPercent());
        s.setSwingTakeProfitPercent(p.getSwingTakeProfitPercent());
        s.setSwingMaxHoldingDays(p.getSwingMaxHoldingDays());
        // 리스크 루프와 일일 손실 한도도 관리자 opt-in 전용 — env 시드 없이 항상 꺼진 상태로 시작한다.
        s.setRiskLoopEnabled(false);
        s.setDailyLossLimitAmount(0);
        // 하루 제안 한도는 화면에서 조정 가능한 값으로 승격 — 최초 시드값만 env(.env 미설정 시 기본값)에서 가져온다.
        s.setDailyMaxProposals(p.getDailyMaxProposals());
        repo.save(s);
    }

    public KiwoomStrategySettings current() {
        return repo.findById(1L).orElseThrow();
    }

    /** A human start action is the sole opt-in required for the unattended trading loops. */
    @Transactional
    public KiwoomStrategySettings activateFullAutomation() {
        KiwoomStrategySettings s = current();
        s.setAutoExecute(true);
        s.setRiskLoopEnabled(true);
        return repo.save(s);
    }

    @Transactional
    public KiwoomStrategySettings save(Update u, String user) {
        KiwoomStrategySettings s = current();
        String before = tradingRulesSummary(s);
        s.setAutoExecute(u.autoExecute);
        s.setAutoExecuteMinConfidence(clamp(u.autoExecuteMinConfidence, 0, 100));
        s.setMaxBuyDepositPercent(clamp(u.maxBuyDepositPercent, 0, 100));
        s.setCandidateReevaluationMinutes(clamp(u.candidateReevaluationMinutes, 15, 240));
        s.setSwingMinChangePercent(clamp(u.swingMinChangePercent, 0.5, 15));
        s.setSwingMaxChangePercent(
                Math.max(
                        s.getSwingMinChangePercent(),
                        clamp(u.swingMaxChangePercent, 0.5, 30)));
        s.setSwingMinVolumeRatio(clamp(u.swingMinVolumeRatio, 1, 20));
        s.setSwingStopLossPercent(clamp(u.swingStopLossPercent, 0, 100));
        s.setSwingTakeProfitPercent(clamp(u.swingTakeProfitPercent, 0, 100));
        s.setSwingMaxHoldingDays(clamp(u.swingMaxHoldingDays, 1, 30));
        s.setRiskLoopEnabled(u.riskLoopEnabled);
        s.setDailyLossLimitAmount(Math.max(0, u.dailyLossLimitAmount));
        s.setDailyMaxProposals(clamp(u.dailyMaxProposals, 1, 200));
        prompts.saveOverride(AiPromptCatalog.KIWOOM_TRADE_STRATEGY, u.prompt, user);
        KiwoomStrategySettings saved = repo.save(s);
        String after = tradingRulesSummary(saved);
        if (!before.equals(after))
            log.info("[자동매매][설정 변경] 사용자={}, 변경 전=[{}], 변경 후=[{}]", user, before, after);
        return saved;
    }

    private String tradingRulesSummary(KiwoomStrategySettings s) {
        return "자동 주문="
                + (s.isAutoExecute() ? "사용" : "중지")
                + ", AI 신뢰도="
                + s.getAutoExecuteMinConfidence()
                + "% 이상, 1회 매수="
                + s.getMaxBuyDepositPercent()
                + "% 이내, 재검토="
                + s.getCandidateReevaluationMinutes()
                + "분, 상승률=+"
                + s.getSwingMinChangePercent()
                + "%~+"
                + s.getSwingMaxChangePercent()
                + "%, 거래량="
                + s.getSwingMinVolumeRatio()
                + "배 이상, 손절/익절=-"
                + s.getSwingStopLossPercent()
                + "%/+"
                + s.getSwingTakeProfitPercent()
                + "%, 보유="
                + s.getSwingMaxHoldingDays()
                + "거래일, 일일 제안 한도="
                + s.getDailyMaxProposals()
                + "건";
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    public record Update(
            boolean autoExecute,
            int autoExecuteMinConfidence,
            double maxBuyDepositPercent,
            int candidateReevaluationMinutes,
            double swingMinChangePercent,
            double swingMaxChangePercent,
            double swingMinVolumeRatio,
            double swingStopLossPercent,
            double swingTakeProfitPercent,
            int swingMaxHoldingDays,
            boolean riskLoopEnabled,
            long dailyLossLimitAmount,
            int dailyMaxProposals,
            String prompt) {}
}
