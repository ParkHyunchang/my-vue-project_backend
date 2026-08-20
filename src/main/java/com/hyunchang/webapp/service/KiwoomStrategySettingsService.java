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
            if (existing.getSwingMaxVolumeRatio() <= 0) {
                existing.setSwingMaxVolumeRatio(5.0);
                changed = true;
            }
            if (existing.getMinMarketCapWon() <= 0) {
                existing.setMinMarketCapWon(300_000_000_000L);
                changed = true;
            }
            if (existing.getMinTradingValueWon() <= 0) {
                existing.setMinTradingValueWon(10_000_000_000L);
                changed = true;
            }
            if (existing.getMaxSpreadPercent() <= 0) {
                existing.setMaxSpreadPercent(0.3);
                changed = true;
            }
            if (existing.getMaxPriceAboveMa20Percent() <= 0) {
                existing.setMaxPriceAboveMa20Percent(10.0);
                changed = true;
            }
            if (existing.getMaxAtrPercent() <= 0) {
                existing.setMaxAtrPercent(4.0);
                changed = true;
            }
            if (existing.getMaxPositions() <= 0) {
                existing.setMaxPositions(3);
                changed = true;
            }
            if (existing.getMaxPositionsPerSector() <= 0) {
                existing.setMaxPositionsPerSector(1);
                changed = true;
            }
            if (existing.getStopLossCooldownTradingDays() <= 0) {
                existing.setStopLossCooldownTradingDays(5);
                changed = true;
            }
            if (existing.getDailyStopLossLimit() <= 0) {
                existing.setDailyStopLossLimit(2);
                changed = true;
            }
            // swingTakeProfitPercent2는 0이 "미설정"이 아니라 "2차 익절 사용 안 함"이라는 의도적인 값이라
            // 손절/익절 비율처럼 여기서 백필하지 않는다 — 관리자가 화면에서 켜기 전까진 계속 꺼진 채로 둔다.
            if (existing.getSwingTakeProfitSplitPercent() <= 0) {
                existing.setSwingTakeProfitSplitPercent(50.0);
                changed = true;
            }
            if (existing.getRequireCatalystForAutoBuy() == null) {
                existing.setRequireCatalystForAutoBuy(true);
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
        s.setSwingMaxVolumeRatio(5.0);
        s.setMinMarketCapWon(300_000_000_000L);
        s.setMinTradingValueWon(10_000_000_000L);
        s.setMaxSpreadPercent(0.3);
        s.setMaxPriceAboveMa20Percent(10.0);
        s.setMaxAtrPercent(4.0);
        s.setSwingStopLossPercent(p.getSwingStopLossPercent());
        s.setSwingTakeProfitPercent(p.getSwingTakeProfitPercent());
        s.setSwingTakeProfitPercent2(p.getSwingTakeProfitPercent2());
        s.setSwingTakeProfitSplitPercent(p.getSwingTakeProfitSplitPercent());
        s.setSwingMaxHoldingDays(p.getSwingMaxHoldingDays());
        // 리스크 루프와 일일 손실 한도도 관리자 opt-in 전용 — env 시드 없이 항상 꺼진 상태로 시작한다.
        s.setRiskLoopEnabled(false);
        s.setDailyLossLimitPercent(0);
        // 하루 신규 매수 체결 건수 한도는 화면에서 조정 가능한 값으로 승격 — 최초 시드값만 env(.env 미설정 시 기본값)에서 가져온다.
        s.setDailyMaxProposals(p.getDailyMaxProposals());
        s.setMaxPositions(3);
        s.setMaxPositionsPerSector(1);
        s.setStopLossCooldownTradingDays(5);
        s.setDailyStopLossLimit(2);
        s.setRequireCatalystForAutoBuy(true);
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
                Math.max(s.getSwingMinChangePercent(), clamp(u.swingMaxChangePercent, 0.5, 30)));
        s.setSwingMinVolumeRatio(clamp(u.swingMinVolumeRatio, 1, 20));
        s.setSwingMaxVolumeRatio(
                Math.max(s.getSwingMinVolumeRatio(), clamp(u.swingMaxVolumeRatio, 1, 20)));
        s.setMinMarketCapWon(clamp(u.minMarketCapWon, 0, 100_000_000_000_000L));
        s.setMinTradingValueWon(clamp(u.minTradingValueWon, 0, 10_000_000_000_000L));
        s.setMaxSpreadPercent(clamp(u.maxSpreadPercent, 0.01, 5));
        s.setMaxPriceAboveMa20Percent(clamp(u.maxPriceAboveMa20Percent, 0, 100));
        s.setMaxAtrPercent(clamp(u.maxAtrPercent, 0.1, 100));
        s.setSwingStopLossPercent(clamp(u.swingStopLossPercent, 0, 100));
        s.setSwingTakeProfitPercent(clamp(u.swingTakeProfitPercent, 0, 100));
        s.setSwingTakeProfitPercent2(
                u.swingTakeProfitPercent2 <= 0
                        ? 0
                        : Math.max(
                                s.getSwingTakeProfitPercent() + 0.1,
                                clamp(u.swingTakeProfitPercent2, 0, 100)));
        s.setSwingTakeProfitSplitPercent(clamp(u.swingTakeProfitSplitPercent, 1, 99));
        s.setSwingMaxHoldingDays(clamp(u.swingMaxHoldingDays, 1, 30));
        s.setRiskLoopEnabled(u.riskLoopEnabled);
        s.setDailyLossLimitPercent(clamp(u.dailyLossLimitPercent, 0, 30));
        s.setDailyMaxProposals(clamp(u.dailyMaxProposals, 1, 200));
        s.setMaxPositions(clamp(u.maxPositions, 1, 20));
        s.setMaxPositionsPerSector(clamp(u.maxPositionsPerSector, 1, 10));
        s.setStopLossCooldownTradingDays(clamp(u.stopLossCooldownTradingDays, 0, 30));
        s.setDailyStopLossLimit(clamp(u.dailyStopLossLimit, 1, 20));
        s.setRequireCatalystForAutoBuy(u.requireCatalystForAutoBuy);
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
                + "~"
                + s.getSwingMaxVolumeRatio()
                + "배, 시총="
                + s.getMinMarketCapWon()
                + "원 이상, 거래대금="
                + s.getMinTradingValueWon()
                + "원 이상, 스프레드="
                + s.getMaxSpreadPercent()
                + "% 이하, MA20 과열="
                + s.getMaxPriceAboveMa20Percent()
                + "% 이하, ATR="
                + s.getMaxAtrPercent()
                + "% 이하, 손절/익절=-"
                + s.getSwingStopLossPercent()
                + "%/+"
                + s.getSwingTakeProfitPercent()
                + "%"
                + (s.getSwingTakeProfitPercent2() > 0
                        ? String.format(
                                ", 2차 익절=+%s%%(분할 %s%%/%s%%)",
                                s.getSwingTakeProfitPercent2(),
                                s.getSwingTakeProfitSplitPercent(),
                                100 - s.getSwingTakeProfitSplitPercent())
                        : "")
                + ", 보유="
                + s.getSwingMaxHoldingDays()
                + "거래일, 일일 신규 매수 체결 건수 한도="
                + s.getDailyMaxProposals()
                + "건, 최대 보유="
                + s.getMaxPositions()
                + "종목, 업종당="
                + s.getMaxPositionsPerSector()
                + "종목, 손절 재매수 제한="
                + s.getStopLossCooldownTradingDays()
                + "거래일, 일일 손절="
                + s.getDailyStopLossLimit()
                + "건, 일일 손실="
                + s.getDailyLossLimitPercent()
                + "%, 자동매수 촉매="
                + (s.isRequireCatalystForAutoBuy() ? "필수" : "선택");
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private long clamp(long v, long min, long max) {
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
            double swingMaxVolumeRatio,
            long minMarketCapWon,
            long minTradingValueWon,
            double maxSpreadPercent,
            double maxPriceAboveMa20Percent,
            double maxAtrPercent,
            double swingStopLossPercent,
            double swingTakeProfitPercent,
            double swingTakeProfitPercent2,
            double swingTakeProfitSplitPercent,
            int swingMaxHoldingDays,
            boolean riskLoopEnabled,
            double dailyLossLimitPercent,
            int dailyMaxProposals,
            int maxPositions,
            int maxPositionsPerSector,
            int stopLossCooldownTradingDays,
            int dailyStopLossLimit,
            boolean requireCatalystForAutoBuy,
            String prompt) {}
}
