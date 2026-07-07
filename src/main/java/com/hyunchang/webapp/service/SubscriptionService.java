package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.Subscription;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.exception.NotFoundException;
import com.hyunchang.webapp.repository.SubscriptionRepository;
import com.hyunchang.webapp.repository.UserRepository;
import com.hyunchang.webapp.util.SecurityUtils;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private static final Set<String> ALLOWED_CYCLES = Set.of("MONTHLY", "YEARLY", "WEEKLY");
    private static final Set<String> ALLOWED_STATUS = Set.of("ACTIVE", "PAUSED", "CANCELED");

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    public SubscriptionService(
            SubscriptionRepository subscriptionRepository, UserRepository userRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
    }

    private User getCurrentUser() {
        String userId = SecurityUtils.getCurrentUserId();
        return userRepository
                .findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<Subscription> findAll() {
        return subscriptionRepository.findByUserOrderByNextBillingDateAsc(getCurrentUser());
    }

    @Transactional(readOnly = true)
    public Subscription findById(Long id) {
        Subscription sub =
                subscriptionRepository
                        .findById(id)
                        .orElseThrow(() -> new NotFoundException("구독을 찾을 수 없습니다."));
        if (!sub.getUser().getUserId().equals(SecurityUtils.getCurrentUserId())) {
            throw new RuntimeException("접근 권한이 없습니다.");
        }
        return sub;
    }

    @Transactional
    public Subscription create(Subscription input) {
        validate(input);
        input.setId(null);
        input.setUser(getCurrentUser());
        if (input.getCurrency() == null || input.getCurrency().isBlank()) input.setCurrency("KRW");
        if (input.getStatus() == null || input.getStatus().isBlank()) input.setStatus("ACTIVE");
        Subscription saved = subscriptionRepository.save(input);
        log.info(
                "[SUBSCRIPTION] user={}({}), CREATE id={} name={} amount={} cycle={} next={}",
                SecurityUtils.getCurrentUserId(),
                SecurityUtils.getCurrentUserRoleName(),
                saved.getId(),
                saved.getName(),
                saved.getAmount(),
                saved.getBillingCycle(),
                saved.getNextBillingDate());
        return saved;
    }

    @Transactional
    public Subscription update(Long id, Subscription input) {
        validate(input);
        Subscription existing = findById(id);
        existing.setName(input.getName());
        existing.setAmount(input.getAmount());
        existing.setCurrency(
                input.getCurrency() == null || input.getCurrency().isBlank()
                        ? "KRW"
                        : input.getCurrency());
        existing.setBillingCycle(input.getBillingCycle());
        existing.setNextBillingDate(input.getNextBillingDate());
        existing.setStartedAt(input.getStartedAt());
        existing.setCategory(input.getCategory());
        existing.setPaymentMethod(input.getPaymentMethod());
        existing.setStatus(
                input.getStatus() == null || input.getStatus().isBlank()
                        ? "ACTIVE"
                        : input.getStatus());
        existing.setColor(input.getColor());
        existing.setMemo(input.getMemo());
        Subscription saved = subscriptionRepository.save(existing);
        log.info(
                "[SUBSCRIPTION] user={}({}), UPDATE id={} name={} amount={} cycle={} next={} status={}",
                SecurityUtils.getCurrentUserId(),
                SecurityUtils.getCurrentUserRoleName(),
                saved.getId(),
                saved.getName(),
                saved.getAmount(),
                saved.getBillingCycle(),
                saved.getNextBillingDate(),
                saved.getStatus());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Subscription sub = findById(id);
        subscriptionRepository.delete(sub);
        log.info(
                "[SUBSCRIPTION] user={}({}), DELETE id={} name={}",
                SecurityUtils.getCurrentUserId(),
                SecurityUtils.getCurrentUserRoleName(),
                sub.getId(),
                sub.getName());
    }

    private void validate(Subscription input) {
        if (input.getName() == null || input.getName().isBlank()) {
            throw new RuntimeException("서비스명을 입력해주세요.");
        }
        if (input.getAmount() == null || input.getAmount() < 0) {
            throw new RuntimeException("금액을 올바르게 입력해주세요.");
        }
        if (input.getBillingCycle() == null || !ALLOWED_CYCLES.contains(input.getBillingCycle())) {
            throw new RuntimeException("결제 주기는 MONTHLY, YEARLY, WEEKLY 중 하나여야 합니다.");
        }
        if (input.getNextBillingDate() == null) {
            throw new RuntimeException("다음 결제일을 입력해주세요.");
        }
        if (input.getStartedAt() != null
                && input.getStartedAt().isAfter(LocalDate.now().plusYears(10))) {
            throw new RuntimeException("시작일이 비정상적입니다.");
        }
        if (input.getStatus() != null
                && !input.getStatus().isBlank()
                && !ALLOWED_STATUS.contains(input.getStatus())) {
            throw new RuntimeException("상태는 ACTIVE, PAUSED, CANCELED 중 하나여야 합니다.");
        }
    }
}
