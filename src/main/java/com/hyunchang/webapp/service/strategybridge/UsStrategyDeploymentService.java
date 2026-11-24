package com.hyunchang.webapp.service.strategybridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.dto.UsStrategyDeploymentRequests.*;
import com.hyunchang.webapp.dto.UsStrategyDeploymentResponse;
import com.hyunchang.webapp.dto.UsStrategyDeploymentResponse.*;
import com.hyunchang.webapp.entity.KiwoomUsTradeProposal.Status;
import com.hyunchang.webapp.entity.UsStrategyDeployment;
import com.hyunchang.webapp.entity.UsStrategyDeploymentAudit;
import com.hyunchang.webapp.repository.*;
import com.hyunchang.webapp.service.KiwoomUsAccountRiskService;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import com.hyunchang.webapp.util.SecurityUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Approval is data-only. No broker methods, auto-start, or risk reset are available here. */
@Service
public class UsStrategyDeploymentService implements UsResearchDeploymentGateway {
    private static final List<Status> OPEN =
            List.of(
                    Status.PROPOSED,
                    Status.SUBMITTING,
                    Status.ORDERED,
                    Status.PARTIALLY_FILLED,
                    Status.CANCEL_REQUESTED,
                    Status.UNKNOWN);
    private final UsStrategyDeploymentRepository repository;
    private final UsStrategyDeploymentAuditRepository audit;
    private final KiwoomUsTradeProposalRepository proposals;
    private final KiwoomUsAccountRiskService risk;
    private final KiwoomUsAutoTradeState control;
    private final KiwoomProperties properties;
    private final ObjectMapper mapper;
    private final ResearchEvidenceVerifier verifier;
    private final boolean bridgeEnabled;
    private final String keyFile;
    private final String marketFile;
    private Clock clock = Clock.systemUTC();

    public UsStrategyDeploymentService(
            UsStrategyDeploymentRepository repository,
            UsStrategyDeploymentAuditRepository audit,
            KiwoomUsTradeProposalRepository proposals,
            KiwoomUsAccountRiskService risk,
            KiwoomUsAutoTradeState control,
            KiwoomProperties properties,
            ObjectMapper mapper,
            @Value("${kiwoom.us.strategy-bridge.enabled:false}") boolean enabled,
            @Value("${kiwoom.us.strategy-bridge.signing-key-file:}") String keyFile,
            @Value("${kiwoom.us.strategy-bridge.market-file:}") String marketFile) {
        this.repository = repository;
        this.audit = audit;
        this.proposals = proposals;
        this.risk = risk;
        this.control = control;
        this.properties = properties;
        this.mapper = mapper;
        this.verifier = new ResearchEvidenceVerifier(mapper);
        this.bridgeEnabled = enabled;
        this.keyFile = keyFile;
        this.marketFile = marketFile;
    }

    void useClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean enabled() {
        return bridgeEnabled;
    }

    @Override
    public boolean managed() {
        // Even disabling the bridge or changing accounts must never reactivate legacy trading.
        return repository.findById(1L).map(UsStrategyDeployment::isManaged).orElse(false);
    }

    private UsStrategyDeployment current() {
        return repository.findById(1L).orElseGet(UsStrategyDeployment::new);
    }

    private UsStrategyDeployment locked() {
        return repository.findForUpdate().orElseGet(UsStrategyDeployment::new);
    }

    private long revision(UsStrategyDeployment row) {
        return row.getRevision() == null ? 0 : row.getRevision();
    }

    private String accountHash() {
        String account = properties.getAccountNo();
        if (account == null || account.isBlank())
            throw new IllegalArgumentException("실계좌 식별 설정이 없습니다.");
        return sha("US:" + account.trim());
    }

    private void requireAccount(UsStrategyDeployment row) {
        if (row.getAccountHash() != null && !row.getAccountHash().equals(accountHash()))
            throw new IllegalArgumentException("승인 계좌와 현재 연결 계좌가 다릅니다. 자동 교체하지 않습니다.");
    }

    @Override
    public void requireAccountScope() {
        requireAccount(current());
    }

    private void requireEnabled() {
        if (!bridgeEnabled) throw new IllegalArgumentException("실계좌 전략 연결은 서버 설정에서 비활성 상태입니다.");
        key();
    }

    private byte[] key() {
        String text = readConfigured(keyFile, 4096).trim();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalArgumentException("연구 서명 키 설정이 올바르지 않습니다.");
        return bytes;
    }

    private String readConfigured(String configured, long maxBytes) {
        try {
            if (configured == null || configured.isBlank()) throw new IllegalArgumentException();
            Path path = Path.of(configured);
            if (!Files.isRegularFile(path) || Files.size(path) > maxBytes)
                throw new IllegalArgumentException();
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception error) {
            // Do not expose server paths or secret values in API errors.
            throw new IllegalArgumentException("연구 연결 파일 설정 또는 접근 권한을 확인하세요.");
        }
    }

    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception error) {
            throw new IllegalArgumentException("연구 자료 JSON이 올바르지 않습니다.");
        }
    }

    private JsonNode verifiedEnvelope(String raw, String kind) {
        JsonNode envelope = parse(raw);
        if (!envelope.isObject()
                || envelope.size() != 2
                || !envelope.path("payloadJson").isTextual()
                || !envelope.path("signature").isTextual())
            throw new IllegalArgumentException("연구 서명 봉투 형식이 올바르지 않습니다.");
        return verifier.verify(
                envelope.get("payloadJson").textValue(),
                envelope.get("signature").textValue(),
                key(),
                kind,
                clock.instant());
    }

    private List<String> approvalBlockers(UsStrategyDeployment row) {
        List<String> blockers = new ArrayList<>();
        if (!bridgeEnabled) blockers.add("실계좌 연결 기능 비활성: 운영에는 아직 적용되지 않았습니다.");
        try {
            requireAccount(row);
            accountHash();
        } catch (IllegalArgumentException e) {
            blockers.add(e.getMessage());
        }
        if (bridgeEnabled) {
            try {
                key();
            } catch (IllegalArgumentException e) {
                blockers.add(e.getMessage());
            }
        }
        if (control.isAutoTrading() || control.isDeciding())
            blockers.add("자동매매를 중지하고 진행 중인 판단이 끝난 뒤 승인하세요.");
        if (row.getLastExecution() != null && row.getLastExecution().startsWith("CLAIMED:"))
            blockers.add("중단 또는 진행 중인 주문 실행을 먼저 대사해야 합니다. 승인으로 실행 기록을 초기화할 수 없습니다.");
        if (!proposals.findByStatusIn(OPEN).isEmpty()) blockers.add("미체결·미확정 주문을 먼저 확인하세요.");
        var status = risk.status();
        if (!status.enabled()
                || status.limitPercent() != 10
                || status.triggered()
                || status.liquidationPending()
                || !status.capitalFlowConfirmed())
            blockers.add("전체 계좌 10% 위험 정책과 입출금 확인이 필요하며 손실 차단 중에는 승인할 수 없습니다.");
        if (status.observedAt() == null
                || Duration.between(status.observedAt().toInstant(), clock.instant())
                                .abs()
                                .getSeconds()
                        > 120) blockers.add("계좌 평가를 새로고침한 뒤 2분 이내에 승인하세요.");
        return blockers;
    }

    public UsStrategyDeploymentResponse status() {
        return response(current());
    }

    private UsStrategyDeploymentResponse response(UsStrategyDeployment row) {
        List<String> blockers = approvalBlockers(row);
        String scope;
        try {
            scope = accountHash().substring(0, 12);
        } catch (IllegalArgumentException e) {
            scope = "";
        }
        Active active = null;
        if (row.isActive() && row.getActivePayload() != null) {
            JsonNode p = parse(row.getActivePayload());
            active =
                    new Active(
                            p.path("candidateId").asText(),
                            p.path("version").asText(),
                            p.path("reportId").asText(),
                            row.getApprovedAt());
        }
        Candidate candidate = null;
        if (row.getPendingEnvelope() != null) {
            JsonNode p = parse(parse(row.getPendingEnvelope()).path("payloadJson").asText());
            List<String> reasons = new ArrayList<>(blockers);
            try {
                JsonNode verified = verifiedEnvelope(row.getPendingEnvelope(), "CANDIDATE");
                matchingMarket(verified);
            } catch (IllegalArgumentException e) {
                reasons.add(e.getMessage());
            }
            candidate =
                    new Candidate(
                            sha(p.toString()),
                            p.path("candidateId").asText(),
                            p.path("name").asText(),
                            p.path("version").asText(),
                            p.path("reportId").asText(),
                            historicalAnnual(p),
                            p.path("forward").path("returnPct").asDouble(),
                            p.path("forward").path("days").asInt(),
                            p.path("forward").path("fillCount").asInt(),
                            reasons.isEmpty(),
                            List.copyOf(reasons));
        }
        return new UsStrategyDeploymentResponse(
                bridgeEnabled,
                revision(row),
                scope,
                row.isManaged(),
                active,
                candidate,
                List.copyOf(blockers),
                row.getLastExecution());
    }

    @Transactional
    public synchronized UsStrategyDeploymentResponse importCandidate(ImportRequest request) {
        requireEnabled();
        verifier.verify(
                request.payloadJson(), request.signature(), key(), "CANDIDATE", clock.instant());
        UsStrategyDeployment row = locked();
        requireAccount(row);
        row.setAccountHash(accountHash());
        try {
            row.setPendingEnvelope(mapper.writeValueAsString(request));
        } catch (Exception e) {
            throw new IllegalArgumentException("검증 보고서 저장 실패");
        }
        repository.saveAndFlush(row);
        event(row, "IMPORTED", "서명된 검증 보고서 등록. 실계좌 전략 변경 및 주문 시작 없음.");
        return response(row);
    }

    @Transactional
    public synchronized UsStrategyDeploymentResponse approve(ApproveRequest request) {
        requireEnabled();
        UsStrategyDeployment row = locked();
        requireAccount(row);
        if (!request.confirmLive()
                || request.expectedRevision() != revision(row)
                || row.getPendingEnvelope() == null)
            throw new IllegalArgumentException("명시적 실계좌 승인과 최신 보고서 상태가 필요합니다.");
        List<String> blockers = approvalBlockers(row);
        if (!blockers.isEmpty()) throw new IllegalArgumentException(String.join(" ", blockers));
        JsonNode p = verifiedEnvelope(row.getPendingEnvelope(), "CANDIDATE");
        matchingMarket(p);
        if (!sha(p.toString()).equals(request.candidateId())
                || !p.path("reportId").asText().equals(request.reportId())
                || !p.path("version").asText().equals(request.version()))
            throw new IllegalArgumentException("확인한 보고서 또는 전략 버전이 변경되었습니다.");
        // Deliberately keep execution state/day and every separate account/risk/holding record.
        row.setActivePayload(p.toString());
        row.setDeploymentId(UUID.randomUUID().toString());
        row.setApprovedAt(clock.instant().toString());
        row.setActive(true);
        row.setManaged(true);
        row.setPendingEnvelope(null);
        row.setLastExecution("APPROVED: 자동매매 시작은 별도입니다.");
        repository.saveAndFlush(row);
        event(row, "APPROVED", p.path("candidateId").asText() + " / " + p.path("version").asText());
        return response(row);
    }

    @Transactional
    public synchronized UsStrategyDeploymentResponse revoke(RevokeRequest request) {
        requireEnabled();
        UsStrategyDeployment row = locked();
        requireAccount(row);
        if (request.expectedRevision() != revision(row)
                || control.isAutoTrading()
                || control.isDeciding()
                || row.getLastExecution() != null && row.getLastExecution().startsWith("CLAIMED:"))
            throw new IllegalArgumentException("자동매매와 진행 중 판단을 중지한 뒤 최신 상태로 철회하세요.");
        row.setActive(false);
        row.setLastExecution("REVOKED: 연구 전략 중지, 이전 전략으로 자동 복귀하지 않습니다.");
        repository.saveAndFlush(row);
        event(row, "REVOKED", "위험·보유·고점·미체결 주문은 초기화하지 않음");
        return response(row);
    }

    private ExecutionContext context(UsStrategyDeployment row) {
        requireEnabled();
        requireAccount(row);
        if (!row.isActive() || !row.isManaged())
            throw new IllegalArgumentException("승인된 실계좌 연구 전략이 없습니다.");
        String result = row.getLastExecution();
        if (result != null && !result.startsWith("APPROVED:") && !result.startsWith("COMPLETED:"))
            throw new IllegalArgumentException("이전 실행을 검토하고 명시적으로 다시 승인해야 합니다.");
        JsonNode active = parse(row.getActivePayload());
        JsonNode market = verifiedEnvelope(readConfigured(marketFile, 1048576), "MARKET");
        if (!market.path("researchCodeHash")
                        .asText()
                        .equals(active.path("researchCodeHash").asText())
                || !market.path("engineVersion")
                        .asText()
                        .equals(active.path("engineVersion").asText()))
            throw new IllegalArgumentException("승인한 전략과 시장자료 코드 버전이 다릅니다.");
        Map<String, ResearchStrategyEngine.Signal> signals = new LinkedHashMap<>();
        for (String symbol : ResearchStrategyEngine.SYMBOLS) {
            JsonNode s = market.path("signals").path(symbol);
            signals.put(
                    symbol,
                    new ResearchStrategyEngine.Signal(
                            s.path("eligible").asBoolean(),
                            s.path("momentum").asDouble(),
                            s.path("vol20").asDouble(),
                            s.path("vol63").asDouble(),
                            s.path("riskrank").asDouble()));
        }
        return new ExecutionContext(
                row.getDeploymentId(),
                active.path("candidateId").asText(),
                active.path("version").asText(),
                LocalDate.parse(market.path("dataThrough").asText()),
                Instant.parse(market.path("issuedAt").asText()),
                Map.copyOf(signals),
                row.getExecutionState(),
                sha(market.path("dataThrough").asText() + ":" + market.path("signals").toString()));
    }

    private void matchingMarket(JsonNode candidate) {
        JsonNode market = verifiedEnvelope(readConfigured(marketFile, 1048576), "MARKET");
        if (!candidate.path("reportId").asText().equals(market.path("reportId").asText())
                || !candidate
                        .path("dataThrough")
                        .asText()
                        .equals(market.path("dataThrough").asText()))
            throw new IllegalArgumentException("최신 시장 보고서가 변경되었습니다. 연구실에서 보고서를 다시 내보내세요.");
    }

    private double historicalAnnual(JsonNode candidate) {
        for (JsonNode period : candidate.path("historical").path("periods"))
            if (period.path("period").asText().equals("continuous")
                    && period.path("costBps").asInt() == 25)
                return period.path("annualizedPct").asDouble();
        return 0;
    }

    @Override
    public ExecutionContext executionContext() {
        return context(current());
    }

    @Override
    @Transactional
    public synchronized boolean claim(
            String deploymentId,
            LocalDate signalDate,
            String expectedMarketHash,
            String nextStateJson) {
        UsStrategyDeployment row = locked();
        ExecutionContext current = context(row);
        if (!current.deploymentId().equals(deploymentId)
                || !current.signalDate().equals(signalDate)
                || !current.marketHash().equals(expectedMarketHash)) return false;
        if (!control.isAutoTrading() || control.isEmergencyStopped()) return false;
        risk.requireBuyAllowed();
        if (row.getClaimedSignalDate() != null
                && !signalDate.isAfter(LocalDate.parse(row.getClaimedSignalDate()))) return false;
        if (nextStateJson == null
                || nextStateJson.length() > 32768
                || !parse(nextStateJson).isObject())
            throw new IllegalArgumentException("전략 실행 상태가 올바르지 않습니다.");
        row.setClaimedSignalDate(signalDate.toString());
        row.setExecutionState(nextStateJson);
        row.setLastExecution("CLAIMED: " + signalDate + " / 재시도 금지");
        repository.saveAndFlush(row);
        event(row, "CLAIMED", signalDate.toString());
        return true;
    }

    @Override
    @Transactional
    public synchronized void finish(
            String deploymentId, LocalDate signalDate, String outcome, String detail) {
        UsStrategyDeployment row = locked();
        requireAccount(row);
        if (!Objects.equals(row.getDeploymentId(), deploymentId)
                || !Objects.equals(row.getClaimedSignalDate(), signalDate.toString()))
            throw new IllegalArgumentException("실행 기록의 승인 버전 또는 날짜가 변경되었습니다.");
        String summary = outcome + ": " + signalDate + " / " + (detail == null ? "" : detail);
        row.setLastExecution(summary.substring(0, Math.min(summary.length(), 950)));
        repository.saveAndFlush(row);
        event(row, "EXECUTED", row.getLastExecution());
    }

    private void event(UsStrategyDeployment row, String event, String detail) {
        audit.save(
                new UsStrategyDeploymentAudit(
                        clock.instant().toString(),
                        SecurityUtils.getCurrentUserId(),
                        row.getAccountHash(),
                        row.getDeploymentId(),
                        event,
                        detail));
    }

    static String sha(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
