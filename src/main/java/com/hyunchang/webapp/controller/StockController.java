package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.dto.StockHeatmapResponseDto;
import com.hyunchang.webapp.dto.StockHeatmapSectorDto;
import com.hyunchang.webapp.dto.StockNewsDto;
import com.hyunchang.webapp.dto.StockPriceDto;
import com.hyunchang.webapp.dto.StockQuotaDto;
import com.hyunchang.webapp.dto.StockQuoteDto;
import com.hyunchang.webapp.dto.StockSearchResultDto;
import com.hyunchang.webapp.service.StockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Stock", description = "주식 대시보드 API")
@RestController
@RequestMapping("/api/stock")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @Operation(summary = "국내 시총 Top 10 (KOSPI)")
    @GetMapping("/top10/kr")
    public ResponseEntity<List<StockQuoteDto>> getTop10KR() {
        return ResponseEntity.ok(stockService.getTop10KR());
    }

    @Operation(summary = "국내 시총 Top 10 (KOSDAQ)")
    @GetMapping("/top10/kosdaq")
    public ResponseEntity<List<StockQuoteDto>> getTop10KOSDAQ() {
        return ResponseEntity.ok(stockService.getTop10KOSDAQ());
    }

    @Operation(summary = "미국 시총 Top 10")
    @GetMapping("/top10/us")
    public ResponseEntity<List<StockQuoteDto>> getTop10US() {
        return ResponseEntity.ok(stockService.getTop10US());
    }

    @Operation(summary = "국내 주식 히트맵 (Yahoo Finance 배치, 쿼터 소모 없음, 매 30분 자동 갱신)")
    @GetMapping("/heatmap/kr")
    public ResponseEntity<StockHeatmapResponseDto> getHeatmapKR() {
        return ResponseEntity.ok(stockService.getHeatmapKR());
    }

    @Operation(summary = "주식 뉴스 (RSS) — market: KR(국내) | US(해외) | ALL(전체, 기본값)")
    @GetMapping("/news")
    public ResponseEntity<List<StockNewsDto>> getNews(
            @RequestParam(defaultValue = "KR") String market) {
        return ResponseEntity.ok(stockService.getNews(market));
    }

    @Operation(summary = "종목 검색 (Yahoo Finance 프록시, 쿼터 소모 없음)")
    @GetMapping("/search")
    public ResponseEntity<List<StockSearchResultDto>> searchStocks(
            @RequestParam(required = false, defaultValue = "") String q) {
        if (q.isBlank()) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(stockService.searchStocks(q));
    }

    @Operation(summary = "포트폴리오 개별 종목 시세 조회 (캐시 우선)")
    @GetMapping("/quote")
    public ResponseEntity<?> getQuote(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "us") String market) {
        StockPriceDto dto = stockService.getQuote(symbol, market);
        if (dto == null) return ResponseEntity.status(503)
            .body(Map.of("error", "시세를 가져올 수 없습니다. API 한도를 확인하세요."));
        return ResponseEntity.ok(dto);
    }

    @Operation(summary = "일일 API 쿼타 현황 (KR 09:00 / US 23:30 KST 자동 갱신)")
    @GetMapping("/quota")
    public ResponseEntity<StockQuotaDto> getQuota() {
        return ResponseEntity.ok(stockService.getQuota());
    }

    @Operation(summary = "잔고 조회 상태 (KFTC 연동 필요)")
    @GetMapping("/balance/status")
    public ResponseEntity<Map<String, Object>> getBalanceStatus() {
        // KFTC 오픈뱅킹 승인 후 실제 연동 구현 예정
        return ResponseEntity.ok(Map.of(
            "connected", false,
            "message", "KFTC 오픈뱅킹 연동이 필요합니다. openbanking.or.kr에서 신청하세요."
        ));
    }
}
