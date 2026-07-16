package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** Approval workflow for a proposal. Broker submission remains gated by KIWOOM_TRADE_ENABLED. */
@Service
public class KiwoomProposalOrderService {
 private final KiwoomTradeProposalRepository proposals; private final KiwoomTradeService trade; private final KiwoomProperties props; private final KiwoomWebsocketClient events;
 public KiwoomProposalOrderService(KiwoomTradeProposalRepository proposals,KiwoomTradeService trade,KiwoomProperties props,KiwoomWebsocketClient events){this.proposals=proposals;this.trade=trade;this.props=props;this.events=events;}
 public Result approve(long id){KiwoomTradeProposal p=find(id);if(p.getAction()==KiwoomTradeProposal.Action.HOLD)return fail("HOLD 제안은 승인할 주문이 없습니다.");if(p.getStatus()!=KiwoomTradeProposal.Status.PROPOSED)return fail("PROPOSED 상태의 제안만 승인할 수 있습니다.");if(hasGuards(p))return fail("안전 경고가 있는 제안은 승인할 수 없습니다.");p.approve();proposals.save(p);return ok(p,"제안을 승인했습니다. 주문은 아직 실행되지 않습니다.");}
 public Result reject(long id,String reason){KiwoomTradeProposal p=find(id);if(p.getStatus()!=KiwoomTradeProposal.Status.PROPOSED&&p.getStatus()!=KiwoomTradeProposal.Status.APPROVED)return fail("제안은 이미 처리되었습니다.");p.reject(reason==null||reason.isBlank()?"사용자 거절":reason);proposals.save(p);return ok(p,"제안을 거절했습니다.");}
 public Result draft(long id){KiwoomTradeProposal p=find(id);if(p.getStatus()!=KiwoomTradeProposal.Status.APPROVED)return fail("승인된 제안만 주문 초안으로 만들 수 있습니다.");p.draft();proposals.save(p);return ok(p,"주문 초안을 만들었습니다. 최종 확인 전에는 전송되지 않습니다.");}
 public Result execute(long id,boolean confirmed){KiwoomTradeProposal p=find(id);if(!confirmed)return fail("최종 확인이 필요합니다.");if(p.getStatus()!=KiwoomTradeProposal.Status.ORDER_DRAFT)return fail("주문 초안 상태의 제안만 전송할 수 있습니다.");if(!props.isTradeEnabled())return fail("주문 전송이 비활성화되어 있습니다. 현재는 안전한 dry-run 상태입니다.");try{JsonNode response=trade.placeOrder(new KiwoomTradeService.OrderRequest(p.getAction().name(),p.getStockCode(),p.getQuantity(),p.getLimitPrice(),p.getOrderType().name(),"KRX")).block(Duration.ofSeconds(20));p.ordered(response==null?"":response.toString());proposals.save(p);events.publishEvent("order","승인된 주문을 키움에 전송했습니다: "+p.getStockCode());return ok(p,"주문 전송 요청이 완료되었습니다.");}catch(Exception e){p.orderFailed(trim(e.getMessage()));proposals.save(p);events.publishEvent("error","주문 전송 실패: "+trim(e.getMessage()));return fail("주문 전송 실패: "+trim(e.getMessage()));}}
 private KiwoomTradeProposal find(long id){return proposals.findById(id).orElseThrow(()->new IllegalArgumentException("제안을 찾을 수 없습니다."));} private boolean hasGuards(KiwoomTradeProposal p){return p.getGuardFlags()!=null&&!p.getGuardFlags().isBlank();} private String trim(String s){return s==null?"unknown":s.substring(0,Math.min(500,s.length()));} private Result ok(KiwoomTradeProposal p,String message){return new Result(true,message,p);} private Result fail(String message){return new Result(false,message,null);} public record Result(boolean success,String message,KiwoomTradeProposal proposal){}
}
