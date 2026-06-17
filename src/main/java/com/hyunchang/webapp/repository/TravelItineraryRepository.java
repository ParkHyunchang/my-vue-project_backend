package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.TravelItinerary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TravelItineraryRepository extends JpaRepository<TravelItinerary, Long> {

    // 임박한 일정 먼저 (출발일 오름차순)
    List<TravelItinerary> findByUserUserIdOrderByStartDateAscIdDesc(String userId);

    Optional<TravelItinerary> findByIdAndUserUserId(Long id, String userId);
}
