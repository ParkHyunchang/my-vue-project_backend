package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.common.web.ApiResponses;
import com.hyunchang.webapp.entity.TravelItinerary;
import com.hyunchang.webapp.entity.TravelLog;
import com.hyunchang.webapp.entity.TravelWishlist;
import com.hyunchang.webapp.service.TravelGeocodeService;
import com.hyunchang.webapp.service.TravelPlannerService;
import com.hyunchang.webapp.service.TravelService;
import com.hyunchang.webapp.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Travel", description = "여행 API — AI 플래너 · 다녀온 곳 · 버킷리스트")
@RestController
@RequestMapping("/api/travel")
public class TravelController {

    private static final String MENU_PATH = "/travel";

    private final TravelService travelService;
    private final TravelPlannerService travelPlannerService;
    private final TravelGeocodeService travelGeocodeService;
    private final MenuAccessGuard menuAccessGuard;

    public TravelController(
            TravelService travelService,
            TravelPlannerService travelPlannerService,
            TravelGeocodeService travelGeocodeService,
            MenuAccessGuard menuAccessGuard) {
        this.travelService = travelService;
        this.travelPlannerService = travelPlannerService;
        this.travelGeocodeService = travelGeocodeService;
        this.menuAccessGuard = menuAccessGuard;
    }

    private boolean hasAccess() {
        return menuAccessGuard.hasAccess(MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return menuAccessGuard.forbidden();
    }

    // ── 버킷리스트 ─────────────────────────────────────────────────

    public record WishlistRequest(
            String title,
            String country,
            String city,
            Integer priority,
            String targetPeriod,
            Long estBudget,
            String memo) {}

    @Operation(summary = "버킷리스트 전체 조회")
    @GetMapping("/wishlist")
    public ResponseEntity<?> getWishlist() {
        if (!hasAccess()) return forbidden();
        List<TravelWishlist> list = travelService.getWishlist(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(list);
    }

    @Operation(summary = "버킷리스트 추가")
    @PostMapping("/wishlist")
    public ResponseEntity<?> addWishlist(@RequestBody WishlistRequest req) {
        if (!hasAccess()) return forbidden();
        if (req.title() == null || req.title().isBlank()) {
            return ResponseEntity.badRequest().body("가고 싶은 곳(제목)은 필수입니다.");
        }
        TravelWishlist created =
                travelService.addWishlist(
                        SecurityUtils.getCurrentUserId(),
                        req.title().trim(),
                        trimOrNull(req.country()),
                        trimOrNull(req.city()),
                        req.priority(),
                        trimOrNull(req.targetPeriod()),
                        req.estBudget(),
                        trimOrNull(req.memo()));
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "버킷리스트 수정")
    @PutMapping("/wishlist/{id}")
    public ResponseEntity<?> updateWishlist(
            @PathVariable Long id, @RequestBody WishlistRequest req) {
        if (!hasAccess()) return forbidden();
        TravelWishlist updated =
                travelService.updateWishlist(
                        SecurityUtils.getCurrentUserId(),
                        id,
                        trimOrNull(req.title()),
                        trimOrNull(req.country()),
                        trimOrNull(req.city()),
                        req.priority(),
                        trimOrNull(req.targetPeriod()),
                        req.estBudget(),
                        trimOrNull(req.memo()));
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "버킷리스트 삭제")
    @DeleteMapping("/wishlist/{id}")
    public ResponseEntity<?> deleteWishlist(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        travelService.deleteWishlist(SecurityUtils.getCurrentUserId(), id);
        return ApiResponses.deletedMessage();
    }

    // ── 다녀온 곳 ──────────────────────────────────────────────────

    public record VisitedRequest(
            String title,
            String country,
            String city,
            Double lat,
            Double lng,
            String startDate,
            String endDate,
            Integer rating,
            String memo) {}

    @Operation(summary = "다녀온 곳 전체 조회")
    @GetMapping("/visited")
    public ResponseEntity<?> getVisited() {
        if (!hasAccess()) return forbidden();
        List<TravelLog> list = travelService.getVisited(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(list);
    }

    @Operation(summary = "다녀온 곳 추가")
    @PostMapping("/visited")
    public ResponseEntity<?> addVisited(@RequestBody VisitedRequest req) {
        if (!hasAccess()) return forbidden();
        if (req.title() == null || req.title().isBlank()) {
            return ResponseEntity.badRequest().body("장소명(제목)은 필수입니다.");
        }
        TravelLog created =
                travelService.addVisited(
                        SecurityUtils.getCurrentUserId(),
                        req.title().trim(),
                        trimOrNull(req.country()),
                        trimOrNull(req.city()),
                        req.lat(),
                        req.lng(),
                        toLocalDate(req.startDate()),
                        toLocalDate(req.endDate()),
                        req.rating(),
                        trimOrNull(req.memo()));
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "다녀온 곳 수정")
    @PutMapping("/visited/{id}")
    public ResponseEntity<?> updateVisited(@PathVariable Long id, @RequestBody VisitedRequest req) {
        if (!hasAccess()) return forbidden();
        TravelLog updated =
                travelService.updateVisited(
                        SecurityUtils.getCurrentUserId(),
                        id,
                        trimOrNull(req.title()),
                        trimOrNull(req.country()),
                        trimOrNull(req.city()),
                        req.lat(),
                        req.lng(),
                        toLocalDate(req.startDate()),
                        toLocalDate(req.endDate()),
                        req.rating(),
                        trimOrNull(req.memo()));
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "다녀온 곳 삭제")
    @DeleteMapping("/visited/{id}")
    public ResponseEntity<?> deleteVisited(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        travelService.deleteVisited(SecurityUtils.getCurrentUserId(), id);
        return ApiResponses.deletedMessage();
    }

    // ── AI 플래너 ──────────────────────────────────────────────────

    public record PlanRequest(
            String destination,
            Integer days,
            String companions,
            String style,
            String budget,
            Boolean includeFlight,
            Boolean includeStay) {}

    @Operation(summary = "AI 여행 플래너 — 목적지·기간·스타일 기반 일자별 일정 생성")
    @PostMapping("/plan")
    public ResponseEntity<?> plan(@RequestBody PlanRequest req) {
        if (!hasAccess()) return forbidden();
        if (req == null || req.destination() == null || req.destination().isBlank()) {
            return ResponseEntity.badRequest().body("목적지가 필요합니다.");
        }
        int days = req.days() == null ? 3 : Math.min(14, Math.max(1, req.days()));
        // 미지정(null)이면 포함으로 간주
        boolean includeFlight = req.includeFlight() == null || req.includeFlight();
        boolean includeStay = req.includeStay() == null || req.includeStay();
        Map<String, Object> result =
                travelPlannerService.plan(
                        req.destination().trim(),
                        days,
                        req.companions(),
                        req.style(),
                        req.budget(),
                        includeFlight,
                        includeStay);
        return ResponseEntity.ok(result);
    }

    public record RefineRequest(
            String destination, Integer days, Object plan, String instruction) {}

    @Operation(summary = "AI 일정 채팅 수정 — 현재 일정 + 요청을 받아 수정된 일정 반환")
    @PostMapping("/refine")
    public ResponseEntity<?> refine(@RequestBody RefineRequest req) {
        if (!hasAccess()) return forbidden();
        if (req == null || req.plan() == null) {
            return ResponseEntity.badRequest().body("현재 일정이 필요합니다.");
        }
        if (req.instruction() == null || req.instruction().isBlank()) {
            return ResponseEntity.badRequest().body("수정 요청을 입력하세요.");
        }
        int days = req.days() == null ? 3 : Math.min(14, Math.max(1, req.days()));
        Map<String, Object> result =
                travelPlannerService.refine(
                        req.destination() == null ? "" : req.destination().trim(),
                        days,
                        req.plan(),
                        req.instruction().trim());
        return ResponseEntity.ok(result);
    }

    // ── 예정 일정 ──────────────────────────────────────────────────

    public record ItineraryRequest(
            String title,
            String destination,
            String startDate,
            String endDate,
            Integer days,
            Object itinerary,
            String memo) {}

    @Operation(summary = "예정 일정 전체 조회")
    @GetMapping("/itinerary")
    public ResponseEntity<?> getItineraries() {
        if (!hasAccess()) return forbidden();
        List<TravelItinerary> list = travelService.getItineraries(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(list);
    }

    @Operation(summary = "예정 일정 저장 (AI 플래너 결과 또는 직접 작성)")
    @PostMapping("/itinerary")
    public ResponseEntity<?> addItinerary(@RequestBody ItineraryRequest req) {
        if (!hasAccess()) return forbidden();
        if (req.title() == null || req.title().isBlank()) {
            return ResponseEntity.badRequest().body("일정 제목은 필수입니다.");
        }
        TravelItinerary created =
                travelService.addItinerary(
                        SecurityUtils.getCurrentUserId(),
                        req.title().trim(),
                        trimOrNull(req.destination()),
                        toLocalDate(req.startDate()),
                        toLocalDate(req.endDate()),
                        req.days(),
                        req.itinerary(),
                        trimOrNull(req.memo()));
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "예정 일정 수정")
    @PutMapping("/itinerary/{id}")
    public ResponseEntity<?> updateItinerary(
            @PathVariable Long id, @RequestBody ItineraryRequest req) {
        if (!hasAccess()) return forbidden();
        TravelItinerary updated =
                travelService.updateItinerary(
                        SecurityUtils.getCurrentUserId(),
                        id,
                        trimOrNull(req.title()),
                        trimOrNull(req.destination()),
                        toLocalDate(req.startDate()),
                        toLocalDate(req.endDate()),
                        req.days(),
                        req.itinerary(),
                        trimOrNull(req.memo()));
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "예정 일정 삭제")
    @DeleteMapping("/itinerary/{id}")
    public ResponseEntity<?> deleteItinerary(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        travelService.deleteItinerary(SecurityUtils.getCurrentUserId(), id);
        return ApiResponses.deletedMessage();
    }

    // ── 위치 검색 (지오코딩 프록시) ─────────────────────────────────

    @Operation(summary = "위치 검색 — OSM Nominatim 지오코딩 프록시 (다녀온 곳 핀 지정용)")
    @GetMapping("/geocode")
    public ResponseEntity<?> geocode(@RequestParam(name = "q") String q) {
        if (!hasAccess()) return forbidden();
        String json = travelGeocodeService.search(q);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(json);
    }

    // ── 유틸 ──────────────────────────────────────────────────────

    private String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private LocalDate toLocalDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
