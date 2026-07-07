package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.TravelWishlist;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TravelWishlistRepository extends JpaRepository<TravelWishlist, Long> {

    List<TravelWishlist> findByUserUserIdOrderByPriorityAscIdDesc(String userId);

    Optional<TravelWishlist> findByIdAndUserUserId(Long id, String userId);
}
