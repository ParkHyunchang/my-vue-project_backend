package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.StockHolding;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.repository.StockHoldingRepository;
import com.hyunchang.webapp.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class StockHoldingService {

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

        return stockHoldingRepository.save(holding);
    }

    public StockHolding updateHolding(String userId, Long id, Long quantity, Double avgPrice) {
        StockHolding holding = stockHoldingRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("보유 종목을 찾을 수 없습니다."));

        holding.setQuantity(quantity);
        holding.setAvgPrice(avgPrice);
        return stockHoldingRepository.save(holding);
    }

    public void deleteHolding(String userId, Long id) {
        StockHolding holding = stockHoldingRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("보유 종목을 찾을 수 없습니다."));
        stockHoldingRepository.delete(holding);
    }
}
