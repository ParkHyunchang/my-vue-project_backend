package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.Subscription;
import com.hyunchang.webapp.entity.User;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByUserOrderByNextBillingDateAsc(User user);
}
