package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.dto.StockPriceDto;
import com.hyunchang.webapp.entity.*;
import com.hyunchang.webapp.repository.*;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Generates and stores proposals only. This class never calls placeOrder. */
@Service
public class KiwoomStrategyService {
 private final KiwoomProperties props; private final KiwoomTradeService trade; private final KiwoomAutoTradeState state;
 private final StockService stocks; private final AiPromptService prompts; private final AiProviderChain ai; private final ObjectMapper json;
 private final KiwoomWatchItemRepository watch; private final KiwoomStrategyRunRepository runs; private final KiwoomTradeProposalRepository proposals; private final KiwoomWebsocketClient events; private final KiwoomProposalOrderService orders; private final KrxOpenApiService krx; private final KiwoomStrategySettingsService settings;
 public KiwoomStrategyService(KiwoomProperties props, KiwoomTradeService trade, KiwoomAutoTradeState state, StockService stocks, AiPromptService prompts, AiProviderChain ai, ObjectMapper json, KiwoomWatchItemRepository watch, KiwoomStrategyRunRepository runs, KiwoomTradeProposalRepository proposals, KiwoomWebsocketClient events, KiwoomProposalOrderService orders, KrxOpenApiService krx, KiwoomStrategySettingsService settings) { this.props=props;this.trade=trade;this.state=state;this.stocks=stocks;this.prompts=prompts;this.ai=ai;this.json=json;this.watch=watch;this.runs=runs;this.proposals=proposals;this.events=events;this.orders=orders;this.krx=krx;this.settings=settings; }
 @Scheduled(cron="0 0/30 9-15 * * MON-FRI", zone="Asia/Seoul") public void scheduledDecision(){ if(props.getStrategy().isEnabled() && state.isAutoTrading() && props.isConfigured() && marketOpen()) { try { runDecision("SCHEDULE"); } catch (IllegalStateException ignored) {} } }
 public synchronized DecisionResult runDecision(String by) {
  if(state.isEmergencyStopped()) throw new IllegalStateException("긴급 중지 상태에서는 전략 판단을 실행할 수 없습니다.");
  if(!state.tryStartDecision()) throw new IllegalStateException("이미 전략 판단이 실행 중입니다.");
  KiwoomStrategyRun run=new KiwoomStrategyRun(); run.setTriggeredBy("SCHEDULE".equals(by)?KiwoomStrategyRun.TriggeredBy.SCHEDULE:KiwoomStrategyRun.TriggeredBy.MANUAL);
  try { events.publishEvent("strategy", "AI 전략 판단을 시작합니다. (dry-run)");
   JsonNode deposit=trade.getDeposit().block(Duration.ofSeconds(10)); JsonNode balance=trade.getBalance().block(Duration.ofSeconds(10));
   List<KiwoomWatchItem> items=watch.findAll(); Map<String,String> universe=new LinkedHashMap<>(); StringBuilder watchText=new StringBuilder();
   for(KiwoomWatchItem item:items){ universe.put(item.getStockCode(), item.getStockName()); watchText.append(quoteLine(item.getStockCode(),item.getStockName(),item.getNote())).append('\n'); }
   String holdings=balance == null ? "조회 데이터 없음" : balance.toString();
   Map<String,String> vars=Map.of("현재시각",LocalDateTime.now(ZoneId.of("Asia/Seoul")).toString(),"예수금",String.valueOf(number(deposit,"ord_alow_amt","entr")),"보유종목",holdings,"관심종목",watchText.toString(),"스윙지표",swingSignals(universe.keySet()));
   String prompt=prompts.render(AiPromptCatalog.KIWOOM_TRADE_STRATEGY,vars); run.setPromptChars(prompt.length());
   AiProviderChain.ChainResult result=ai.analyze(prompt,compact(prompt),true);
   if(!result.success()){run.setStatus(KiwoomStrategyRun.Status.BLOCKED);run.setErrorMessage("AI provider unavailable"+(result.retryAt()==null?"":"; retry="+result.retryAt()));runs.save(run);return new DecisionResult(run.getId(),run.getStatus().name(),0);}
   run.setProviderName(result.providerName());run.setModel(result.model()); JsonNode root=parse(result.text()); if(root==null || !root.has("decisions")){run.setStatus(KiwoomStrategyRun.Status.PARSE_FAILED);run.setErrorMessage("AI JSON parsing failed");runs.save(run);return new DecisionResult(run.getId(),run.getStatus().name(),0);}
   run.setMarketView(root.path("marketView").asText(""));run.setStatus(KiwoomStrategyRun.Status.SUCCESS);runs.save(run);int saved=0;
   Set<String> unique=new HashSet<>(); for(JsonNode d:root.path("decisions")){ KiwoomTradeProposal p=validated(d,universe,unique); if(p==null) continue; applyGuardFlags(p); p.setRun(run);proposals.save(p);saved++; if("SCHEDULE".equals(by)) autoSubmit(p); }
   state.markRun();events.publishEvent("strategy","AI 전략 제안 "+saved+"건을 생성했습니다. 주문은 실행되지 않았습니다.");return new DecisionResult(run.getId(),run.getStatus().name(),saved);
  } catch(Exception e){run.setStatus(KiwoomStrategyRun.Status.FAILED);run.setErrorMessage(trim(e.getMessage()));runs.save(run);events.publishEvent("error","전략 판단 실패: "+trim(e.getMessage()));return new DecisionResult(run.getId(),run.getStatus().name(),0);
  } finally {state.finishDecision();}
 }
 private void autoSubmit(KiwoomTradeProposal proposal){ if(!settings.current().isAutoExecute()) return; KiwoomProposalOrderService.Result result=orders.autoExecute(proposal.getId()); if(result.success()) events.publishEvent("order","Automatic policy submitted: "+proposal.getStockCode()); else events.publishEvent("strategy","Automatic submission skipped: "+proposal.getStockCode()+" ("+result.message()+")"); }
 public List<String> subscriptionCodes(){return watch.findAll().stream().map(KiwoomWatchItem::getStockCode).toList();}
 private KiwoomTradeProposal validated(JsonNode d, Map<String,String> universe, Set<String> unique){try{String code=d.path("stockCode").asText(); KiwoomTradeProposal.Action action=KiwoomTradeProposal.Action.valueOf(d.path("action").asText()); if(!code.matches("\\d{6}")||!universe.containsKey(code)||!unique.add(code)||d.path("confidence").asInt(-1)<0||d.path("confidence").asInt()>100)return null; if(action==KiwoomTradeProposal.Action.BUY&&!hasVerifiedSwingSignal(code))return null;int qty=d.path("quantity").asInt(); if(action!=KiwoomTradeProposal.Action.HOLD&&qty<=0)return null; KiwoomTradeProposal p=new KiwoomTradeProposal();p.setAction(action);p.setStockCode(code);p.setStockName(d.path("stockName").asText(universe.get(code)));p.setQuantity(Math.max(qty,0));p.setConfidence(d.path("confidence").asInt());p.setReason(d.path("reason").asText(""));p.setOrderType(KiwoomTradeProposal.OrderType.LIMIT);if(action!=KiwoomTradeProposal.Action.HOLD){long price=d.path("limitPrice").asLong();if(price<=0)return null;var s=settings.current();p.setLimitPrice(price);p.setStopLossPrice(Math.max(1,Math.round(price*(100-s.getSwingStopLossPercent())/100)));p.setTakeProfitPrice(Math.max(1,Math.round(price*(100+s.getSwingTakeProfitPercent())/100)));p.setMaxHoldingDays(s.getSwingMaxHoldingDays());}return p;}catch(Exception e){return null;}}
 private void applyGuardFlags(KiwoomTradeProposal p){
  if(p.getAction()==KiwoomTradeProposal.Action.HOLD)return;
  List<String> flags=new ArrayList<>();
  LocalDateTime now=LocalDateTime.now(ZoneId.of("Asia/Seoul"));
  if(!marketOpen())flags.add("MARKET_CLOSED");
  if(p.getLimitPrice()!=null && p.getLimitPrice()*p.getQuantity()>props.getStrategy().getMaxOrderAmount())flags.add("MAX_ORDER_AMOUNT");
  LocalDateTime start=now.toLocalDate().atStartOfDay();
  long today=proposals.countByActionInAndCreatedAtGreaterThanEqual(List.of(KiwoomTradeProposal.Action.BUY,KiwoomTradeProposal.Action.SELL),start);
  if(today>=props.getStrategy().getDailyMaxProposals())flags.add("DAILY_LIMIT");
  if(proposals.existsByStockCodeAndActionInAndCreatedAtGreaterThanEqual(p.getStockCode(),List.of(KiwoomTradeProposal.Action.BUY,KiwoomTradeProposal.Action.SELL),now.minusMinutes(props.getStrategy().getCooldownMinutes())))flags.add("SYMBOL_COOLDOWN");
  p.setGuardFlags(String.join(",",flags));
 }
 private String swingSignals(Set<String> watchCodes){try{Map<String,KrxOpenApiService.KrSwingCandidate> indexed=new HashMap<>();for(KrxOpenApiService.KrSwingCandidate c:krx.getShortSwingCandidates(200)){indexed.put(c.symbol().replaceAll("\\.(KS|KQ)$",""),c);}StringBuilder text=new StringBuilder();for(String code:watchCodes){KrxOpenApiService.KrSwingCandidate c=indexed.get(code);if(c!=null)text.append(code).append(" change=").append(c.changePercent()).append("% volumeRatio=").append(c.volumeRatio()).append(" (20d avg)\n");}return text.length()==0?"No verified swing signal data. Do not propose BUY.":text.toString();}catch(Exception e){return "Swing signal lookup unavailable. Do not propose BUY.";}}
 private boolean hasVerifiedSwingSignal(String code){try{return krx.getShortSwingCandidates(200).stream().anyMatch(c->code.equals(c.symbol().replaceAll("\\.(KS|KQ)$","")));}catch(Exception e){return false;}}
 private JsonNode parse(String s){try{int a=s.indexOf('{'),b=s.lastIndexOf('}');return a<0||b<a?null:json.readTree(s.substring(a,b+1));}catch(Exception e){return null;}}
 private String quoteLine(String code,String name,String note){StockPriceDto q=stocks.getQuote(code,"KR");return code+" "+name+" price="+(q==null?"unknown":Math.round(q.getPrice()))+(note==null||note.isBlank()?"":" note="+note);}
 private String compact(String prompt){return prompt.length()>7000?prompt.substring(0,7000):prompt;} private boolean marketOpen(){LocalTime t=LocalTime.now(ZoneId.of("Asia/Seoul"));return !t.isBefore(LocalTime.of(9,0))&&!t.isAfter(LocalTime.of(15,30));} private long number(JsonNode n,String... names){if(n!=null)for(String x:names)if(n.has(x))return n.path(x).asLong();return 0;} private String trim(String s){return s==null?"unknown":s.substring(0,Math.min(s.length(),500));}
 public record DecisionResult(Long runId,String status,int proposalCount){}
}
