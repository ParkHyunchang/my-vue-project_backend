package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.StockNewsDto;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** KRX 가격·거래량 1차 후보에 DART 공시와 종목별 뉴스를 붙여 단기 매매 촉매를 확인한다. 후보 선별은 코드가 맡고, 최종 해석만 AI 프롬프트에 전달한다. */
@Service
public class ShortSwingCandidateService {
    private static final Logger log = LoggerFactory.getLogger(ShortSwingCandidateService.class);

    private static final int MAX_SCREENED_CANDIDATES = 20;
    private static final int MAX_CATALYST_CANDIDATES = 12;
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    // 후보별 DART 공시·뉴스 병렬 수집 풀 — 순차 수집 시 후보 20개 × 수초가 그대로 응답 지연이 된다
    private static final ExecutorService CATALYST_POOL = Executors.newFixedThreadPool(8);
    // 촉매 수집 전체 시간 예산 — 초과분은 생략하고 확보된 후보만으로 진행
    private static final long CATALYST_BUDGET_MS = 25_000L;

    private final KrxOpenApiService krxOpenApiService;
    private final DartFinancialService dartFinancialService;
    private final StockSymbolNewsService stockSymbolNewsService;
    private final YahooFinanceService yahooFinanceService;
    private final FinancialDataService financialDataService;

    private volatile List<KrCandidateCatalyst> cache = List.of();
    private volatile long cacheTime = 0;
    private volatile List<UsCandidateSignal> usCache = List.of();
    private volatile long usCacheTime = 0;
    // KR/US 수집 락 분리 — 하나의 락을 공유하면 KR 수집이 오래 걸릴 때 US 조회까지 줄을 선다
    private final Object krLock = new Object();
    private final Object usLock = new Object();

    public ShortSwingCandidateService(
            KrxOpenApiService krxOpenApiService,
            DartFinancialService dartFinancialService,
            StockSymbolNewsService stockSymbolNewsService,
            YahooFinanceService yahooFinanceService,
            FinancialDataService financialDataService) {
        this.krxOpenApiService = krxOpenApiService;
        this.dartFinancialService = dartFinancialService;
        this.stockSymbolNewsService = stockSymbolNewsService;
        this.yahooFinanceService = yahooFinanceService;
        this.financialDataService = financialDataService;
    }

    public List<KrCandidateCatalyst> getKrCandidatesWithCatalysts(int limit) {
        if (limit <= 0) return List.of();
        if (isFresh()) return cache.stream().limit(limit).toList();

        synchronized (krLock) {
            if (!isFresh()) {
                cache = collectCandidates();
                cacheTime = System.currentTimeMillis();
            }
        }
        return cache.stream().limit(limit).toList();
    }

    private List<KrCandidateCatalyst> collectCandidates() {
        List<KrxOpenApiService.KrSwingCandidate> screened =
                krxOpenApiService.getShortSwingCandidates(MAX_SCREENED_CANDIDATES);
        List<CompletableFuture<KrCandidateCatalyst>> futures = new ArrayList<>();
        for (KrxOpenApiService.KrSwingCandidate candidate : screened) {
            futures.add(
                    CompletableFuture.supplyAsync(() -> buildCatalyst(candidate), CATALYST_POOL));
        }

        // 스크리닝 순서(거래량 배율 내림차순)를 유지한 채 촉매 확인된 후보만 채택
        List<KrCandidateCatalyst> result = new ArrayList<>();
        long deadline = System.currentTimeMillis() + CATALYST_BUDGET_MS;
        for (CompletableFuture<KrCandidateCatalyst> future : futures) {
            if (result.size() >= MAX_CATALYST_CANDIDATES) {
                future.cancel(true);
                continue;
            }
            try {
                long remaining = Math.max(1, deadline - System.currentTimeMillis());
                KrCandidateCatalyst catalyst = future.get(remaining, TimeUnit.MILLISECONDS);
                if (catalyst != null) result.add(catalyst);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("[SwingCandidate] KR 촉매 수집 시간 예산 초과 — 해당 후보 생략");
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("[SwingCandidate] KR 촉매 수집 실패: {}", e.getMessage());
            }
        }
        return result;
    }

    private KrCandidateCatalyst buildCatalyst(KrxOpenApiService.KrSwingCandidate candidate) {
        List<DartFinancialService.PositiveDisclosure> disclosures =
                dartFinancialService.recentPositiveDisclosures(candidate.symbol(), 3);
        List<CatalystNews> news = extractCatalystNews(candidate);
        if (disclosures.isEmpty() && news.isEmpty()) return null;
        return new KrCandidateCatalyst(candidate, disclosures, news);
    }

    @PreDestroy
    void shutdownCatalystPool() {
        CATALYST_POOL.shutdown();
    }

    private List<CatalystNews> extractCatalystNews(KrxOpenApiService.KrSwingCandidate candidate) {
        List<StockNewsDto> fetched =
                stockSymbolNewsService.fetchForSymbol(
                        candidate.symbol(), "KR", candidate.name(), null);
        return fetched.stream()
                .filter(this::isConcretePositiveCatalyst)
                .sorted(
                        Comparator.comparing(
                                        StockNewsDto::getPubDate,
                                        Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparing(
                                        StockNewsDto::getTitle,
                                        Comparator.nullsLast(String::compareTo)))
                .limit(3)
                .map(
                        news ->
                                new CatalystNews(
                                        news.getTitle(),
                                        news.getPubDate(),
                                        news.getSource(),
                                        news.getLink()))
                .toList();
    }

    private boolean isConcretePositiveCatalyst(StockNewsDto news) {
        String text =
                ((news.getTitle() == null ? "" : news.getTitle())
                                + " "
                                + (news.getDescription() == null ? "" : news.getDescription()))
                        .toLowerCase(Locale.ROOT);
        return text.contains("수주")
                || text.contains("공급계약")
                || text.contains("계약 체결")
                || text.contains("실적 개선")
                || text.contains("사상 최대")
                || text.contains("자사주")
                || text.contains("무상증자")
                || text.contains("승인")
                || text.contains("임상")
                || text.contains("신제품")
                || text.contains("투자의견 상향");
    }

    private boolean isFresh() {
        return cacheTime > 0 && (System.currentTimeMillis() - cacheTime) < CACHE_TTL_MS;
    }

    /**
     * 미국 대형주 후보에 Alpha Vantage 종목별 감성 및 Yahoo 컨센서스를 결합한다. 긍정 감성(+0.25 이상)·관련도 높은 기사가 3건 이상인 경우만 촉매가
     * 확인된 후보로 반환한다. 무료 Alpha Vantage 한도를 고려해 상위 12개만 조회하고 30분 캐시한다.
     */
    public List<UsCandidateSignal> getUsCandidatesWithSignals(int limit) {
        if (limit <= 0) return List.of();
        if (isUsFresh()) return usCache.stream().limit(limit).toList();
        synchronized (usLock) {
            if (!isUsFresh()) {
                usCache = collectUsCandidates();
                usCacheTime = System.currentTimeMillis();
            }
        }
        return usCache.stream().limit(limit).toList();
    }

    /**
     * 캐시 프리워밍. 후보 수집(KRX 히스토리·DART·뉴스)이 사용자 요청 시점에 수행되면 포트폴리오 진단 응답이 그만큼 늦어지므로, 캐시 TTL(30분)보다 짧은
     * 주기로 미리 채워 사용자 요청은 항상 캐시를 타게 한다. 기동 직후 바로 1회 실행해 재시작 후 콜드 윈도우를 최소화한다 (DART corpCode 등 의존 데이터는
     * 첫 사용 시 lazy 로드되므로 기동 직후 실행해도 무방).
     */
    @Scheduled(initialDelay = 15_000L, fixedDelay = 25 * 60 * 1000L)
    public void prewarmCaches() {
        try {
            int kr = getKrCandidatesWithCatalysts(MAX_CATALYST_CANDIDATES).size();
            int us = getUsCandidatesWithSignals(MAX_CATALYST_CANDIDATES).size();
            log.info("[SwingCandidate] 캐시 프리워밍 완료: KR {}개, US {}개", kr, us);
        } catch (Exception e) {
            log.warn("[SwingCandidate] 캐시 프리워밍 실패: {}", e.getMessage());
        }
    }

    private List<UsCandidateSignal> collectUsCandidates() {
        List<YahooFinanceService.RawQuote> quotes = yahooFinanceService.fetchTopMarketCapUs(12);
        if (quotes.isEmpty()) {
            // 시총 상위 조회가 비는 건 항상 소스 실패다 — 빈 결과로 덮어써 30분간 후보가
            // 사라지게 하지 않고 직전 결과를 유지한다 (다음 갱신에서 재시도).
            log.warn("[SwingCandidate] US 스크리너 응답 없음 — 직전 후보 {}개 유지", usCache.size());
            return usCache;
        }
        List<UsCandidateSignal> result = new ArrayList<>();
        for (YahooFinanceService.RawQuote quote : quotes) {
            if (quote.price() <= 0 || quote.changePercent() <= 0) continue;
            List<StockNewsDto> positive =
                    stockSymbolNewsService
                            .fetchForSymbol(quote.symbol(), "US", quote.name(), quote.name())
                            .stream()
                            .filter(
                                    news ->
                                            news.getSentimentScore() != null
                                                    && news.getRelevanceScore() != null)
                            .filter(
                                    news ->
                                            news.getSentimentScore() >= 0.25
                                                    && news.getRelevanceScore() >= 0.5)
                            .limit(5)
                            .toList();
            if (positive.size() < 3) continue;

            double averageSentiment =
                    positive.stream()
                            .map(StockNewsDto::getSentimentScore)
                            .mapToDouble(Double::doubleValue)
                            .average()
                            .orElse(0);
            String consensus = financialDataService.stockSummary(quote.symbol(), "US");
            result.add(new UsCandidateSignal(quote, positive, averageSentiment, consensus));
        }
        return result.stream()
                .sorted(Comparator.comparingDouble(UsCandidateSignal::averageSentiment).reversed())
                .toList();
    }

    private boolean isUsFresh() {
        return usCacheTime > 0 && (System.currentTimeMillis() - usCacheTime) < CACHE_TTL_MS;
    }

    public record CatalystNews(String title, String publishedAt, String source, String link) {}

    public record KrCandidateCatalyst(
            KrxOpenApiService.KrSwingCandidate candidate,
            List<DartFinancialService.PositiveDisclosure> disclosures,
            List<CatalystNews> news) {}

    public record UsCandidateSignal(
            YahooFinanceService.RawQuote candidate,
            List<StockNewsDto> positiveSentimentNews,
            double averageSentiment,
            String consensus) {}
}
