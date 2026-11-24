package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.PortfolioAccountDtos.*;
import com.hyunchang.webapp.entity.AccountHolding;
import com.hyunchang.webapp.entity.PortfolioAccount;
import com.hyunchang.webapp.repository.AccountHoldingRepository;
import com.hyunchang.webapp.repository.PortfolioAccountRepository;
import com.hyunchang.webapp.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class PortfolioAccountService {
    private static final Map<String, String> LEGACY_NAMES =
            Map.of(
                    "stock",
                    "기존 장기 기록",
                    "general",
                    "기존 단기 기록",
                    "isa",
                    "기존 ISA 기록",
                    "irp",
                    "기존 IRP 기록");
    private static final Map<String, String> LEGACY_PURPOSES =
            Map.of("stock", "LONG_TERM", "general", "SHORT_TERM", "isa", "ISA", "irp", "IRP");
    private final PortfolioAccountRepository accounts;
    private final AccountHoldingRepository holdings;
    private final UserRepository users;

    public PortfolioAccountService(
            PortfolioAccountRepository accounts,
            AccountHoldingRepository holdings,
            UserRepository users) {
        this.accounts = accounts;
        this.holdings = holdings;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(String userId) {
        Map<String, AccountResponse> result = new LinkedHashMap<>();
        for (String legacy : List.of("stock", "general", "isa", "irp")) {
            String key = "legacy-" + legacy;
            result.put(
                    key,
                    response(
                            key,
                            "UNASSIGNED",
                            LEGACY_NAMES.get(legacy),
                            LEGACY_PURPOSES.get(legacy)));
        }
        for (PortfolioAccount account : accounts.findByUserUserIdOrderByIdAsc(userId))
            result.put(account.getAccountKey(), response(account));
        return List.copyOf(result.values());
    }

    public AccountResponse create(String userId, AccountRequest request) {
        PortfolioAccount account = new PortfolioAccount();
        account.setAccountKey(UUID.randomUUID().toString());
        account.setUser(users.findByUserId(userId).orElseThrow(EntityNotFoundException::new));
        apply(account, request);
        return response(accounts.save(account));
    }

    public AccountResponse update(String userId, String key, AccountRequest request) {
        PortfolioAccount account =
                accounts.findByAccountKeyAndUserUserId(key, userId)
                        .orElseGet(
                                () -> {
                                    if (legacyType(key) == null)
                                        throw new EntityNotFoundException("계좌를 찾을 수 없습니다.");
                                    PortfolioAccount entry = new PortfolioAccount();
                                    entry.setAccountKey(key);
                                    entry.setUser(
                                            users.findByUserId(userId)
                                                    .orElseThrow(EntityNotFoundException::new));
                                    return entry;
                                });
        apply(account, request);
        return response(accounts.save(account));
    }

    private void apply(PortfolioAccount account, AccountRequest request) {
        if (request == null
                || request.broker() == null
                || request.purpose() == null
                || !Set.of("UNASSIGNED", "SAMSUNG", "SHINHAN", "KIWOOM", "OTHER")
                        .contains(request.broker())
                || !Set.of("LONG_TERM", "SHORT_TERM", "ISA", "IRP", "OTHER")
                        .contains(request.purpose())
                || request.name() == null
                || request.name().isBlank()
                || request.name().trim().length() > 80)
            throw new IllegalArgumentException("계좌 별칭, 증권사, 운용 목적을 확인해 주세요.");
        account.setBroker(request.broker());
        account.setName(request.name().trim());
        account.setPurpose(request.purpose());
    }

    private PortfolioAccount owned(String userId, String key) {
        if (legacyType(key) != null)
            throw new IllegalArgumentException("기존 기록은 계좌 응답의 holdingsPath를 사용해 주세요.");
        return accounts.findByAccountKeyAndUserUserId(key, userId)
                .orElseThrow(() -> new EntityNotFoundException("계좌를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<HoldingResponse> holdings(String userId, String key) {
        PortfolioAccount account = owned(userId, key);
        return holdings.findByAccountIdOrderByIdAsc(account.getId()).stream()
                .map(this::holdingResponse)
                .toList();
    }

    public HoldingResponse add(String userId, String key, HoldingRequest request) {
        PortfolioAccount account = owned(userId, key);
        if (request == null
                || request.assetType() == null
                || request.market() == null
                || !Set.of("STOCK", "CASH").contains(request.assetType())
                || !Set.of("KR", "US").contains(request.market()))
            throw new IllegalArgumentException("자산 종류와 시장을 확인해 주세요.");
        if (request.name() == null
                || request.name().isBlank()
                || request.name().length() > 100
                || request.symbol() == null
                || !request.symbol().trim().matches("[A-Za-z0-9.^_-]{1,20}"))
            throw new IllegalArgumentException("종목명과 심볼을 확인해 주세요.");
        if ("CASH".equals(request.assetType()) && !"KR".equals(request.market()))
            throw new IllegalArgumentException("현재 수기 현금은 원화만 지원합니다.");
        validateAmounts(request.quantity(), request.avgPrice());
        String symbol = request.symbol().trim().toUpperCase(Locale.ROOT);
        if (holdings.existsByAccountIdAndMarketAndSymbol(account.getId(), request.market(), symbol))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이 계좌에 이미 등록된 종목입니다.");
        AccountHolding holding = new AccountHolding();
        holding.setAccount(account);
        holding.setAssetType(request.assetType());
        holding.setMarket(request.market());
        holding.setName(request.name().trim());
        holding.setSymbol(symbol);
        holding.setQuantity(request.quantity());
        holding.setAvgPrice(
                "CASH".equals(request.assetType()) ? BigDecimal.ONE : request.avgPrice());
        return holdingResponse(holdings.saveAndFlush(holding));
    }

    private AccountHolding ownedHolding(String userId, String key, Long id) {
        PortfolioAccount account = owned(userId, key);
        return holdings.findByIdAndAccountId(id, account.getId())
                .orElseThrow(() -> new EntityNotFoundException("보유 기록을 찾을 수 없습니다."));
    }

    public HoldingResponse updateHolding(
            String userId, String key, Long id, HoldingUpdate request) {
        validateAmounts(request.quantity(), request.avgPrice());
        AccountHolding holding = ownedHolding(userId, key, id);
        holding.setQuantity(request.quantity());
        holding.setAvgPrice(
                "CASH".equals(holding.getAssetType()) ? BigDecimal.ONE : request.avgPrice());
        return holdingResponse(holdings.saveAndFlush(holding));
    }

    public HoldingResponse core(String userId, String key, Long id, boolean core) {
        AccountHolding holding = ownedHolding(userId, key, id);
        holding.setCore(core);
        return holdingResponse(holdings.saveAndFlush(holding));
    }

    public void delete(String userId, String key, Long id) {
        holdings.delete(ownedHolding(userId, key, id));
    }

    private void validateAmounts(BigDecimal quantity, BigDecimal price) {
        if (quantity == null
                || quantity.signum() <= 0
                || !fits(quantity)
                || (price != null && (price.signum() < 0 || !fits(price))))
            throw new IllegalArgumentException("수량과 평균 매입가는 소수점 8자리 이내의 유효한 금액이어야 합니다.");
    }

    private boolean fits(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() <= 8 && normalized.precision() - normalized.scale() <= 16;
    }

    private HoldingResponse holdingResponse(AccountHolding holding) {
        return new HoldingResponse(
                holding.getId(),
                holding.getAccount().getAccountKey(),
                holding.getAssetType(),
                holding.getMarket(),
                "US".equals(holding.getMarket()) ? "USD" : "KRW",
                holding.getName(),
                holding.getSymbol(),
                holding.getQuantity(),
                holding.getAvgPrice(),
                holding.isCore(),
                "MANUAL",
                holding.getUpdatedAt());
    }

    private AccountResponse response(PortfolioAccount account) {
        return response(
                account.getAccountKey(),
                account.getBroker(),
                account.getName(),
                account.getPurpose());
    }

    private AccountResponse response(String key, String broker, String name, String purpose) {
        String legacy = legacyType(key);
        String path =
                legacy == null
                        ? "/api/portfolio/accounts/" + key + "/holdings"
                        : "/api/portfolio/"
                                + ("stock".equals(legacy) ? "" : legacy + "/")
                                + "holdings";
        return new AccountResponse(key, broker, name, purpose, legacy, "MANUAL", path);
    }

    private String legacyType(String key) {
        if (key == null || !key.startsWith("legacy-")) return null;
        String type = key.substring(7);
        return LEGACY_NAMES.containsKey(type) ? type : null;
    }
}
