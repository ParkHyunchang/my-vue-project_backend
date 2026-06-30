package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.GeneralHolding;
import com.hyunchang.webapp.entity.PortfolioAssetType;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.repository.GeneralHoldingRepository;
import com.hyunchang.webapp.repository.UserRepository;
import com.hyunchang.webapp.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class GeneralHoldingService {

    private static final Logger log = LoggerFactory.getLogger(GeneralHoldingService.class);

    private final GeneralHoldingRepository generalHoldingRepository;
    private final UserRepository userRepository;

    public GeneralHoldingService(GeneralHoldingRepository generalHoldingRepository,
                                 UserRepository userRepository) {
        this.generalHoldingRepository = generalHoldingRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<GeneralHolding> getHoldings(String userId) {
        return generalHoldingRepository.findByUserUserIdOrderByIdAsc(userId);
    }

    public GeneralHolding addHolding(String userId, PortfolioAssetType assetType, String market, String name,
                                     String symbol, Long quantity, Double avgPrice) {
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new IllegalStateException("User not found: " + userId));

        PortfolioAssetType resolvedType = assetType == null ? PortfolioAssetType.STOCK : assetType;

        GeneralHolding holding = new GeneralHolding();
        holding.setUser(user);
        holding.setAssetType(resolvedType.name());
        holding.setMarket(market);
        holding.setName(name);
        holding.setSymbol(symbol);
        holding.setQuantity(quantity);
        holding.setAvgPrice(resolvedType == PortfolioAssetType.CASH ? 1.0 : avgPrice);

        GeneralHolding saved = generalHoldingRepository.save(holding);
        log.info("[STOCK/GENERAL] user={}({}), CREATE id={} symbol={} assetType={} market={} quantity={} avgPrice={}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            saved.getId(), saved.getSymbol(), saved.getAssetType(), saved.getMarket(), saved.getQuantity(), saved.getAvgPrice());
        return saved;
    }

    public GeneralHolding updateHolding(String userId, Long id, Long quantity, Double avgPrice) {
        GeneralHolding holding = generalHoldingRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("General holding not found."));

        holding.setQuantity(quantity);
        holding.setAvgPrice(PortfolioAssetType.CASH.name().equals(holding.getAssetType()) ? 1.0 : avgPrice);
        GeneralHolding saved = generalHoldingRepository.save(holding);

        log.info("[STOCK/GENERAL] user={}({}), UPDATE id={} symbol={} quantity={} avgPrice={}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            saved.getId(), saved.getSymbol(), saved.getQuantity(), saved.getAvgPrice());
        return saved;
    }

    public GeneralHolding updateCore(String userId, Long id, boolean core) {
        GeneralHolding holding = generalHoldingRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("General holding not found."));

        holding.setCore(core);
        GeneralHolding saved = generalHoldingRepository.save(holding);

        log.info("[STOCK/GENERAL] user={}({}), CORE id={} symbol={} core={}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            saved.getId(), saved.getSymbol(), core);
        return saved;
    }

    public void deleteHolding(String userId, Long id) {
        GeneralHolding holding = generalHoldingRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("General holding not found."));
        generalHoldingRepository.delete(holding);
        log.info("[STOCK/GENERAL] user={}({}), DELETE id={} symbol={} quantity={} avgPrice={}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            holding.getId(), holding.getSymbol(), holding.getQuantity(), holding.getAvgPrice());
    }
}
