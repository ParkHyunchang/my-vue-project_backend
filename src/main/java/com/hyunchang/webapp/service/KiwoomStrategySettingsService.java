package com.hyunchang.webapp.service;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomStrategySettings;
import com.hyunchang.webapp.repository.KiwoomStrategySettingsRepository;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class KiwoomStrategySettingsService {
 private final KiwoomStrategySettingsRepository repo; private final KiwoomProperties props; private final AiPromptService prompts;
 public KiwoomStrategySettingsService(KiwoomStrategySettingsRepository repo,KiwoomProperties props,AiPromptService prompts){this.repo=repo;this.props=props;this.prompts=prompts;}
 @PostConstruct @Transactional public void seed(){if(repo.existsById(1L))return;KiwoomProperties.Strategy p=props.getStrategy();KiwoomStrategySettings s=new KiwoomStrategySettings();s.setAutoExecute(p.isAutoExecute());s.setAutoExecuteMinConfidence(p.getAutoExecuteMinConfidence());s.setMaxBuyDepositPercent(p.getMaxBuyDepositPercent());s.setSwingStopLossPercent(p.getSwingStopLossPercent());s.setSwingTakeProfitPercent(p.getSwingTakeProfitPercent());s.setSwingMaxHoldingDays(p.getSwingMaxHoldingDays());repo.save(s);}
 public KiwoomStrategySettings current(){return repo.findById(1L).orElseThrow();}
 @Transactional public KiwoomStrategySettings save(Update u,String user){KiwoomStrategySettings s=current();s.setAutoExecute(u.autoExecute);s.setAutoExecuteMinConfidence(clamp(u.autoExecuteMinConfidence,0,100));s.setMaxBuyDepositPercent(clamp(u.maxBuyDepositPercent,0,100));s.setSwingStopLossPercent(clamp(u.swingStopLossPercent,0,100));s.setSwingTakeProfitPercent(clamp(u.swingTakeProfitPercent,0,100));s.setSwingMaxHoldingDays(clamp(u.swingMaxHoldingDays,1,30));prompts.saveOverride(AiPromptCatalog.KIWOOM_TRADE_STRATEGY,u.prompt,user);return repo.save(s);}
 private int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));} private double clamp(double v,double min,double max){return Math.max(min,Math.min(max,v));}
 public record Update(boolean autoExecute,int autoExecuteMinConfidence,double maxBuyDepositPercent,double swingStopLossPercent,double swingTakeProfitPercent,int swingMaxHoldingDays,String prompt){}
}
