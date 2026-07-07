package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.IrpHolding;
import com.hyunchang.webapp.entity.PortfolioAssetType;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.repository.IrpHoldingRepository;
import com.hyunchang.webapp.repository.UserRepository;
import com.hyunchang.webapp.util.SecurityUtils;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class IrpHoldingService {

    private static final Logger log = LoggerFactory.getLogger(IrpHoldingService.class);

    private final IrpHoldingRepository irpHoldingRepository;
    private final UserRepository userRepository;

    public IrpHoldingService(
            IrpHoldingRepository irpHoldingRepository, UserRepository userRepository) {
        this.irpHoldingRepository = irpHoldingRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<IrpHolding> getHoldings(String userId) {
        return irpHoldingRepository.findByUserUserIdOrderByIdAsc(userId);
    }

    public IrpHolding addHolding(
            String userId,
            PortfolioAssetType assetType,
            String market,
            String name,
            String symbol,
            Long quantity,
            Double avgPrice) {
        User user =
                userRepository
                        .findByUserId(userId)
                        .orElseThrow(() -> new IllegalStateException("User not found: " + userId));

        PortfolioAssetType resolvedType = assetType == null ? PortfolioAssetType.STOCK : assetType;

        IrpHolding holding = new IrpHolding();
        holding.setUser(user);
        holding.setAssetType(resolvedType.name());
        holding.setMarket(market);
        holding.setName(name);
        holding.setSymbol(symbol);
        holding.setQuantity(quantity);
        holding.setAvgPrice(resolvedType == PortfolioAssetType.CASH ? 1.0 : avgPrice);

        IrpHolding saved = irpHoldingRepository.save(holding);
        log.info(
                "[STOCK/IRP] user={}({}), CREATE id={} symbol={} assetType={} market={} quantity={} avgPrice={}",
                userId,
                SecurityUtils.getCurrentUserRoleName(),
                saved.getId(),
                saved.getSymbol(),
                saved.getAssetType(),
                saved.getMarket(),
                saved.getQuantity(),
                saved.getAvgPrice());
        return saved;
    }

    public IrpHolding updateHolding(String userId, Long id, Long quantity, Double avgPrice) {
        IrpHolding holding =
                irpHoldingRepository
                        .findByIdAndUserUserId(id, userId)
                        .orElseThrow(() -> new IllegalArgumentException("IRP holding not found."));

        holding.setQuantity(quantity);
        holding.setAvgPrice(
                PortfolioAssetType.CASH.name().equals(holding.getAssetType()) ? 1.0 : avgPrice);
        IrpHolding saved = irpHoldingRepository.save(holding);

        log.info(
                "[STOCK/IRP] user={}({}), UPDATE id={} symbol={} quantity={} avgPrice={}",
                userId,
                SecurityUtils.getCurrentUserRoleName(),
                saved.getId(),
                saved.getSymbol(),
                saved.getQuantity(),
                saved.getAvgPrice());
        return saved;
    }

    public IrpHolding updateCore(String userId, Long id, boolean core) {
        IrpHolding holding =
                irpHoldingRepository
                        .findByIdAndUserUserId(id, userId)
                        .orElseThrow(() -> new IllegalArgumentException("IRP holding not found."));

        holding.setCore(core);
        IrpHolding saved = irpHoldingRepository.save(holding);

        log.info(
                "[STOCK/IRP] user={}({}), CORE id={} symbol={} core={}",
                userId,
                SecurityUtils.getCurrentUserRoleName(),
                saved.getId(),
                saved.getSymbol(),
                core);
        return saved;
    }

    public void deleteHolding(String userId, Long id) {
        IrpHolding holding =
                irpHoldingRepository
                        .findByIdAndUserUserId(id, userId)
                        .orElseThrow(() -> new IllegalArgumentException("IRP holding not found."));
        irpHoldingRepository.delete(holding);
        log.info(
                "[STOCK/IRP] user={}({}), DELETE id={} symbol={} quantity={} avgPrice={}",
                userId,
                SecurityUtils.getCurrentUserRoleName(),
                holding.getId(),
                holding.getSymbol(),
                holding.getQuantity(),
                holding.getAvgPrice());
    }
}
