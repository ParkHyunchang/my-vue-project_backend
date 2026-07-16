package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.*;
import com.hyunchang.webapp.repository.*;
import com.hyunchang.webapp.service.KiwoomStrategyService;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/kiwoom/strategy") @PreAuthorize("hasRole('ADMIN')")
public class KiwoomStrategyController {
 private final KiwoomStrategyService strategy; private final KiwoomProperties props; private final KiwoomWatchItemRepository watch; private final KiwoomStrategyRunRepository runs; private final KiwoomTradeProposalRepository proposals;
 public KiwoomStrategyController(KiwoomStrategyService strategy,KiwoomProperties props,KiwoomWatchItemRepository watch,KiwoomStrategyRunRepository runs,KiwoomTradeProposalRepository proposals){this.strategy=strategy;this.props=props;this.watch=watch;this.runs=runs;this.proposals=proposals;}
 @GetMapping("/watchlist") public List<KiwoomWatchItem> list(){return watch.findAll();}
 @PostMapping("/watchlist") public ResponseEntity<?> add(@RequestBody WatchRequest r){if(r.stockCode()==null||!r.stockCode().matches("\\d{6}"))return ResponseEntity.badRequest().body(Map.of("message","종목코드는 6자리 숫자여야 합니다."));if(watch.existsByStockCode(r.stockCode()))return ResponseEntity.status(409).body(Map.of("message","이미 관심종목에 있습니다."));KiwoomWatchItem x=new KiwoomWatchItem();x.setStockCode(r.stockCode());x.setStockName(r.stockName()==null||r.stockName().isBlank()?r.stockCode():r.stockName());x.setNote(r.note());return ResponseEntity.ok(watch.save(x));}
 @DeleteMapping("/watchlist/{id}") public ResponseEntity<Void> remove(@PathVariable Long id){watch.deleteById(id);return ResponseEntity.noContent().build();}
 @PostMapping("/decide") public KiwoomStrategyService.DecisionResult decide(){return strategy.runDecision("MANUAL");}
 @GetMapping("/config") public Map<String,Object> config(){return Map.of("enabled",props.getStrategy().isEnabled(),"maxOrderAmount",props.getStrategy().getMaxOrderAmount(),"dailyMaxProposals",props.getStrategy().getDailyMaxProposals(),"cooldownMinutes",props.getStrategy().getCooldownMinutes(),"orderEnabled",props.isTradeEnabled(),"dryRun",true);}
 @GetMapping("/runs") public List<Map<String,Object>> history(@RequestParam(defaultValue="10") int limit){List<KiwoomStrategyRun> list=runs.findByOrderByIdDesc(PageRequest.of(0,Math.min(Math.max(limit,1),50))).getContent();Map<Long,List<KiwoomTradeProposal>> grouped=new HashMap<>();for(KiwoomTradeProposal p:proposals.findByRunIdInOrderByIdAsc(list.stream().map(KiwoomStrategyRun::getId).toList()))grouped.computeIfAbsent(p.getRun().getId(),k->new ArrayList<>()).add(p);return list.stream().map(r->Map.<String,Object>of("id",r.getId(),"status",r.getStatus(),"triggeredBy",r.getTriggeredBy(),"marketView",r.getMarketView()==null?"":r.getMarketView(),"errorMessage",r.getErrorMessage()==null?"":r.getErrorMessage(),"createdAt",r.getCreatedAt().toString(),"proposals",grouped.getOrDefault(r.getId(),List.of()))).toList();}
 public record WatchRequest(String stockCode,String stockName,String note){}
}
