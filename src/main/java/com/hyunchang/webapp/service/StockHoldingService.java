package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.StockHolding;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.repository.StockHoldingRepository;
import com.hyunchang.webapp.repository.UserRepository;
import com.hyunchang.webapp.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@Transactional
public class StockHoldingService {

    private static final Logger log = LoggerFactory.getLogger(StockHoldingService.class);

    private final StockHoldingRepository stockHoldingRepository;
    private final UserRepository userRepository;

    public StockHoldingService(StockHoldingRepository stockHoldingRepository,
                               UserRepository userRepository) {
        this.stockHoldingRepository = stockHoldingRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<StockHolding> getHoldings(String userId) {
        return stockHoldingRepository.findByUserUserIdOrderByIdAsc(userId);
    }

    public StockHolding addHolding(String userId, String market, String name,
                                   String symbol, Long quantity, Double avgPrice) {
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다: " + userId));

        StockHolding holding = new StockHolding();
        holding.setUser(user);
        holding.setMarket(market);
        holding.setName(name);
        holding.setSymbol(symbol);
        holding.setQuantity(quantity);
        holding.setAvgPrice(avgPrice);

        StockHolding saved = stockHoldingRepository.save(holding);
        log.info("[PORTFOLIO] user={}({}), CREATE id={} symbol={} market={} quantity={} avgPrice={}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            saved.getId(), saved.getSymbol(), saved.getMarket(), saved.getQuantity(), saved.getAvgPrice());
        return saved;
    }

    public StockHolding updateHolding(String userId, Long id, Long quantity, Double avgPrice) {
        StockHolding holding = stockHoldingRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("보유 종목을 찾을 수 없습니다."));

        List<String> diffs = new ArrayList<>();
        if (!Objects.equals(holding.getQuantity(), quantity)) {
            diffs.add(String.format("quantity %s→%s", holding.getQuantity(), quantity));
        }
        if (!Objects.equals(holding.getAvgPrice(), avgPrice)) {
            diffs.add(String.format("avgPrice %s→%s", holding.getAvgPrice(), avgPrice));
        }

        holding.setQuantity(quantity);
        holding.setAvgPrice(avgPrice);
        StockHolding saved = stockHoldingRepository.save(holding);

        log.info("[PORTFOLIO] user={}({}), UPDATE id={} symbol={} {}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            saved.getId(), saved.getSymbol(),
            diffs.isEmpty() ? "(변경 없음)" : String.join(", ", diffs));
        return saved;
    }

    public void deleteHolding(String userId, Long id) {
        StockHolding holding = stockHoldingRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("보유 종목을 찾을 수 없습니다."));
        stockHoldingRepository.delete(holding);
        log.info("[PORTFOLIO] user={}({}), DELETE id={} symbol={} quantity={} avgPrice={}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            holding.getId(), holding.getSymbol(), holding.getQuantity(), holding.getAvgPrice());
    }
}
