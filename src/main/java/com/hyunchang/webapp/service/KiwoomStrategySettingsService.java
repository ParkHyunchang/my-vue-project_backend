package com.hyunchang.webapp.service;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomStrategySettings;
import com.hyunchang.webapp.repository.KiwoomStrategySettingsRepository;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KiwoomStrategySettingsService {
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
        if (repo.existsById(1L)) return;
        KiwoomProperties.Strategy p = props.getStrategy();
        KiwoomStrategySettings s = new KiwoomStrategySettings();
        s.setAutoExecute(false);
        s.setAutoExecuteMinConfidence(p.getAutoExecuteMinConfidence());
        s.setMaxBuyDepositPercent(p.getMaxBuyDepositPercent());
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

    @Transactional
    public KiwoomStrategySettings save(Update u, String user) {
        KiwoomStrategySettings s = current();
        s.setAutoExecute(u.autoExecute);
        s.setAutoExecuteMinConfidence(clamp(u.autoExecuteMinConfidence, 0, 100));
        s.setMaxBuyDepositPercent(clamp(u.maxBuyDepositPercent, 0, 100));
        s.setSwingStopLossPercent(clamp(u.swingStopLossPercent, 0, 100));
        s.setSwingTakeProfitPercent(clamp(u.swingTakeProfitPercent, 0, 100));
        s.setSwingMaxHoldingDays(clamp(u.swingMaxHoldingDays, 1, 30));
        s.setRiskLoopEnabled(u.riskLoopEnabled);
        s.setDailyLossLimitAmount(Math.max(0, u.dailyLossLimitAmount));
        s.setDailyMaxProposals(clamp(u.dailyMaxProposals, 1, 200));
        prompts.saveOverride(AiPromptCatalog.KIWOOM_TRADE_STRATEGY, u.prompt, user);
        return repo.save(s);
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
            double swingStopLossPercent,
            double swingTakeProfitPercent,
            int swingMaxHoldingDays,
            boolean riskLoopEnabled,
            long dailyLossLimitAmount,
            int dailyMaxProposals,
            String prompt) {}
}
