package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.entity.TravelItinerary;
import com.hyunchang.webapp.entity.TravelLog;
import com.hyunchang.webapp.entity.TravelWishlist;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.repository.TravelItineraryRepository;
import com.hyunchang.webapp.repository.TravelLogRepository;
import com.hyunchang.webapp.repository.TravelWishlistRepository;
import com.hyunchang.webapp.repository.UserRepository;
import com.hyunchang.webapp.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 여행 기능의 데이터 CRUD — 버킷리스트(가고 싶은 곳)와 다녀온 곳(여행 기록).
 * AI 플래너는 별도 서비스(TravelPlannerService)에서 처리한다.
 */
@Service
@Transactional
public class TravelService {

    private static final Logger log = LoggerFactory.getLogger(TravelService.class);

    private final TravelWishlistRepository wishlistRepository;
    private final TravelLogRepository logRepository;
    private final TravelItineraryRepository itineraryRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TravelService(TravelWishlistRepository wishlistRepository,
                         TravelLogRepository logRepository,
                         TravelItineraryRepository itineraryRepository,
                         UserRepository userRepository) {
        this.wishlistRepository = wishlistRepository;
        this.logRepository = logRepository;
        this.itineraryRepository = itineraryRepository;
        this.userRepository = userRepository;
    }

    private User requireUser(String userId) {
        return userRepository.findByUserId(userId)
            .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다: " + userId));
    }

    private static Integer clampPriority(Integer p) {
        if (p == null) return 2;
        return Math.min(3, Math.max(1, p));
    }

    private static Integer clampRating(Integer r) {
        if (r == null) return null;
        return Math.min(5, Math.max(1, r));
    }

    // ── 버킷리스트 ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TravelWishlist> getWishlist(String userId) {
        return wishlistRepository.findByUserUserIdOrderByPriorityAscIdDesc(userId);
    }

    public TravelWishlist addWishlist(String userId, String title, String country, String city,
                                      Integer priority, String targetPeriod, Long estBudget, String memo) {
        TravelWishlist w = new TravelWishlist();
        w.setUser(requireUser(userId));
        w.setTitle(title);
        w.setCountry(country);
        w.setCity(city);
        w.setPriority(clampPriority(priority));
        w.setTargetPeriod(targetPeriod);
        w.setEstBudget(estBudget);
        w.setMemo(memo);
        TravelWishlist saved = wishlistRepository.save(w);
        log.info("[TRAVEL/WISHLIST] user={}({}), CREATE id={} title={}",
            userId, SecurityUtils.getCurrentUserRoleName(), saved.getId(), saved.getTitle());
        return saved;
    }

    public TravelWishlist updateWishlist(String userId, Long id, String title, String country, String city,
                                         Integer priority, String targetPeriod, Long estBudget, String memo) {
        TravelWishlist w = wishlistRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("버킷리스트 항목을 찾을 수 없습니다."));
        if (title != null && !title.isBlank()) w.setTitle(title);
        w.setCountry(country);
        w.setCity(city);
        w.setPriority(clampPriority(priority));
        w.setTargetPeriod(targetPeriod);
        w.setEstBudget(estBudget);
        w.setMemo(memo);
        return wishlistRepository.save(w);
    }

    public void deleteWishlist(String userId, Long id) {
        TravelWishlist w = wishlistRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("버킷리스트 항목을 찾을 수 없습니다."));
        wishlistRepository.delete(w);
        log.info("[TRAVEL/WISHLIST] user={}({}), DELETE id={} title={}",
            userId, SecurityUtils.getCurrentUserRoleName(), w.getId(), w.getTitle());
    }

    // ── 다녀온 곳 ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TravelLog> getVisited(String userId) {
        return logRepository.findByUserUserIdOrderByStartDateDescIdDesc(userId);
    }

    /** 기간 정규화: 도착일이 비었으면 출발일과 동일(당일), 도착<출발이면 두 값 교환. */
    private static LocalDate[] normalizeRange(LocalDate start, LocalDate end) {
        if (start == null) return new LocalDate[]{null, null};
        if (end == null) return new LocalDate[]{start, start};
        return end.isBefore(start) ? new LocalDate[]{end, start} : new LocalDate[]{start, end};
    }

    public TravelLog addVisited(String userId, String title, String country, String city,
                                Double lat, Double lng, LocalDate startDate, LocalDate endDate,
                                Integer rating, String memo) {
        LocalDate[] range = normalizeRange(startDate, endDate);
        TravelLog t = new TravelLog();
        t.setUser(requireUser(userId));
        t.setTitle(title);
        t.setCountry(country);
        t.setCity(city);
        t.setLat(lat);
        t.setLng(lng);
        t.setStartDate(range[0]);
        t.setEndDate(range[1]);
        t.setRating(clampRating(rating));
        t.setMemo(memo);
        TravelLog saved = logRepository.save(t);
        log.info("[TRAVEL/LOG] user={}({}), CREATE id={} title={} lat={} lng={}",
            userId, SecurityUtils.getCurrentUserRoleName(), saved.getId(), saved.getTitle(), saved.getLat(), saved.getLng());
        return saved;
    }

    public TravelLog updateVisited(String userId, Long id, String title, String country, String city,
                                   Double lat, Double lng, LocalDate startDate, LocalDate endDate,
                                   Integer rating, String memo) {
        TravelLog t = logRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("여행 기록을 찾을 수 없습니다."));
        LocalDate[] range = normalizeRange(startDate, endDate);
        if (title != null && !title.isBlank()) t.setTitle(title);
        t.setCountry(country);
        t.setCity(city);
        t.setLat(lat);
        t.setLng(lng);
        t.setStartDate(range[0]);
        t.setEndDate(range[1]);
        t.setRating(clampRating(rating));
        t.setMemo(memo);
        return logRepository.save(t);
    }

    public void deleteVisited(String userId, Long id) {
        TravelLog t = logRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("여행 기록을 찾을 수 없습니다."));
        logRepository.delete(t);
        log.info("[TRAVEL/LOG] user={}({}), DELETE id={} title={}",
            userId, SecurityUtils.getCurrentUserRoleName(), t.getId(), t.getTitle());
    }

    // ── 예정 일정 ──────────────────────────────────────────────────

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("[TRAVEL/ITINERARY] itinerary 직렬화 실패: {}", e.getMessage());
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<TravelItinerary> getItineraries(String userId) {
        return itineraryRepository.findByUserUserIdOrderByStartDateAscIdDesc(userId);
    }

    public TravelItinerary addItinerary(String userId, String title, String destination,
                                        LocalDate startDate, LocalDate endDate, Integer days,
                                        Object itinerary, String memo) {
        LocalDate[] range = normalizeRange(startDate, endDate);
        TravelItinerary it = new TravelItinerary();
        it.setUser(requireUser(userId));
        it.setTitle(title);
        it.setDestination(destination);
        it.setStartDate(range[0]);
        it.setEndDate(range[1]);
        it.setDays(days);
        it.setItinerary(toJson(itinerary));
        it.setMemo(memo);
        TravelItinerary saved = itineraryRepository.save(it);
        log.info("[TRAVEL/ITINERARY] user={}({}), CREATE id={} title={} days={}",
            userId, SecurityUtils.getCurrentUserRoleName(), saved.getId(), saved.getTitle(), saved.getDays());
        return saved;
    }

    public TravelItinerary updateItinerary(String userId, Long id, String title, String destination,
                                           LocalDate startDate, LocalDate endDate, Integer days,
                                           Object itinerary, String memo) {
        TravelItinerary it = itineraryRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("예정 일정을 찾을 수 없습니다."));
        LocalDate[] range = normalizeRange(startDate, endDate);
        if (title != null && !title.isBlank()) it.setTitle(title);
        it.setDestination(destination);
        it.setStartDate(range[0]);
        it.setEndDate(range[1]);
        it.setDays(days);
        if (itinerary != null) it.setItinerary(toJson(itinerary));
        it.setMemo(memo);
        return itineraryRepository.save(it);
    }

    public void deleteItinerary(String userId, Long id) {
        TravelItinerary it = itineraryRepository.findByIdAndUserUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("예정 일정을 찾을 수 없습니다."));
        itineraryRepository.delete(it);
        log.info("[TRAVEL/ITINERARY] user={}({}), DELETE id={} title={}",
            userId, SecurityUtils.getCurrentUserRoleName(), it.getId(), it.getTitle());
    }
}
