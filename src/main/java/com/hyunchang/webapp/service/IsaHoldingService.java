package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.IsaHolding;
import com.hyunchang.webapp.entity.PortfolioAssetType;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.repository.IsaHoldingRepository;
import com.hyunchang.webapp.repository.UserRepository;
import com.hyunchang.webapp.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class IsaHoldingService {

    private static final Logger log = LoggerFactory.getLogger(IsaHoldingService.class);

    private final IsaHoldingRepository isaHoldingRepository;
    private final UserRepository userRepository;

    public IsaHoldingService(IsaHoldingRepository isaHoldingRepository,
                             UserRepository userRepository) {
        this.isaHoldingRepository = isaHoldingRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<IsaHolding> getHoldings(String userId) {
        return isaHoldingRepository.findByUserUserIdOrderByIdAsc(userId);
    }

    public IsaHolding addHolding(String userId, PortfolioAssetType assetType, String market, String name,
                                 String symbol, Long quantity, Double avgPrice) {
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new IllegalStateException("User not found: " + userId));

        PortfolioAssetType resolvedType = assetType == null ? PortfolioAssetType.STOCK : assetType;

        IsaHolding holding = new IsaHolding();
        holding.setUser(user);
        holding.setAssetType(resolvedType.name());
        holding.setMarket(market);
        holding.setName(name);
        holding.setSymbol(symbol);
        holding.setQuantity(quantity);
        holding.setAvgPrice(resolvedType == PortfolioAssetType.CASH ? 1.0 : avgPrice);

        IsaHolding saved = isaHoldingRepository.save(holding);
        log.info("[STOCK/ISA] user={}({}), CREATE id={} symbol={} assetType={} market={} quantity={} avgPrice={}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            saved.getId(), saved.getSymbol(), saved.getAssetType(), saved.getMarket(), saved.getQuantity(), saved.getAvgPrice());
        return saved;
    }

    public IsaHolding updateHolding(String userId, Long id, Long quantity, Double avgPrice) {
        IsaHolding holding = isaHoldingRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("ISA holding not found."));

        holding.setQuantity(quantity);
        holding.setAvgPrice(PortfolioAssetType.CASH.name().equals(holding.getAssetType()) ? 1.0 : avgPrice);
        IsaHolding saved = isaHoldingRepository.save(holding);

        log.info("[STOCK/ISA] user={}({}), UPDATE id={} symbol={} quantity={} avgPrice={}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            saved.getId(), saved.getSymbol(), saved.getQuantity(), saved.getAvgPrice());
        return saved;
    }

    public IsaHolding updateCore(String userId, Long id, boolean core) {
        IsaHolding holding = isaHoldingRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("ISA holding not found."));

        holding.setCore(core);
        IsaHolding saved = isaHoldingRepository.save(holding);

        log.info("[STOCK/ISA] user={}({}), CORE id={} symbol={} core={}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            saved.getId(), saved.getSymbol(), core);
        return saved;
    }

    public void deleteHolding(String userId, Long id) {
        IsaHolding holding = isaHoldingRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("ISA holding not found."));
        isaHoldingRepository.delete(holding);
        log.info("[STOCK/ISA] user={}({}), DELETE id={} symbol={} quantity={} avgPrice={}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            holding.getId(), holding.getSymbol(), holding.getQuantity(), holding.getAvgPrice());
    }
}
