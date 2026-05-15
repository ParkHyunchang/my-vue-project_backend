package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.Subscription;
import com.hyunchang.webapp.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByUserOrderByNextBillingDateAsc(User user);
}
