package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.PropertyHolding;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.repository.PropertyHoldingRepository;
import com.hyunchang.webapp.repository.UserRepository;
import com.hyunchang.webapp.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class PropertyHoldingService {

    private static final Logger log = LoggerFactory.getLogger(PropertyHoldingService.class);

    private final PropertyHoldingRepository propertyHoldingRepository;
    private final UserRepository userRepository;

    public PropertyHoldingService(PropertyHoldingRepository propertyHoldingRepository,
                                  UserRepository userRepository) {
        this.propertyHoldingRepository = propertyHoldingRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<PropertyHolding> getHoldings(String userId) {
        return propertyHoldingRepository.findByUserUserIdOrderByIdAsc(userId);
    }

    public PropertyHolding addHolding(String userId, String dealType, String name, String lawdCd,
                                      String sigungu, Double areaM2, Long purchasePrice,
                                      Long monthlyRent, String memo, java.time.LocalDate purchaseDate) {
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다: " + userId));

        PropertyHolding holding = new PropertyHolding();
        holding.setUser(user);
        holding.setDealType(dealType);
        holding.setName(name);
        holding.setLawdCd(lawdCd);
        holding.setSigungu(sigungu);
        holding.setAreaM2(areaM2);
        holding.setPurchasePrice(purchasePrice);
        holding.setMonthlyRent(monthlyRent);
        holding.setMemo(memo);
        holding.setPurchaseDate(purchaseDate);

        PropertyHolding saved = propertyHoldingRepository.save(holding);
        log.info("[PROPERTY/HOLDING] user={}({}), CREATE id={} name={} lawdCd={} dealType={} area={} price={}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            saved.getId(), saved.getName(), saved.getLawdCd(), saved.getDealType(),
            saved.getAreaM2(), saved.getPurchasePrice());
        return saved;
    }

    public PropertyHolding updateHolding(String userId, Long id, Long purchasePrice,
                                         Long monthlyRent, String memo, java.time.LocalDate purchaseDate) {
        PropertyHolding holding = propertyHoldingRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("보유 부동산을 찾을 수 없습니다."));

        holding.setPurchasePrice(purchasePrice);
        holding.setMonthlyRent(monthlyRent);
        holding.setMemo(memo);
        holding.setPurchaseDate(purchaseDate);
        PropertyHolding saved = propertyHoldingRepository.save(holding);

        log.info("[PROPERTY/HOLDING] user={}({}), UPDATE id={} name={} price={} monthlyRent={}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            saved.getId(), saved.getName(), saved.getPurchasePrice(), saved.getMonthlyRent());
        return saved;
    }

    public void deleteHolding(String userId, Long id) {
        PropertyHolding holding = propertyHoldingRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("보유 부동산을 찾을 수 없습니다."));
        propertyHoldingRepository.delete(holding);
        log.info("[PROPERTY/HOLDING] user={}({}), DELETE id={} name={}",
            userId, SecurityUtils.getCurrentUserRoleName(),
            holding.getId(), holding.getName());
    }
}
