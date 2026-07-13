package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.StockNewsDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * KRX 가격·거래량 1차 후보에 DART 공시와 종목별 뉴스를 붙여 단기 매매 촉매를
 * 확인한다. 후보 선별은 코드가 맡고, 최종 해석만 AI 프롬프트에 전달한다.
 */
@Service
public class ShortSwingCandidateService {
    private static final int MAX_SCREENED_CANDIDATES = 20;
    private static final int MAX_CATALYST_CANDIDATES = 12;
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    private final KrxOpenApiService krxOpenApiService;
    private final DartFinancialService dartFinancialService;
    private final StockSymbolNewsService stockSymbolNewsService;
    private final YahooFinanceService yahooFinanceService;
    private final FinancialDataService financialDataService;

    private volatile List<KrCandidateCatalyst> cache = List.of();
    private volatile long cacheTime = 0;
    private volatile List<UsCandidateSignal> usCache = List.of();
    private volatile long usCacheTime = 0;

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

        synchronized (this) {
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
        List<KrCandidateCatalyst> result = new ArrayList<>();
        for (KrxOpenApiService.KrSwingCandidate candidate : screened) {
            List<DartFinancialService.PositiveDisclosure> disclosures =
                    dartFinancialService.recentPositiveDisclosures(candidate.symbol(), 3);
            List<CatalystNews> news = extractCatalystNews(candidate);
            if (disclosures.isEmpty() && news.isEmpty()) continue;

            result.add(new KrCandidateCatalyst(candidate, disclosures, news));
            if (result.size() >= MAX_CATALYST_CANDIDATES) break;
        }
        return result;
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
                                .thenComparing(StockNewsDto::getTitle, Comparator.nullsLast(String::compareTo)))
                .limit(3)
                .map(news -> new CatalystNews(news.getTitle(), news.getPubDate(), news.getSource(), news.getLink()))
                .toList();
    }

    private boolean isConcretePositiveCatalyst(StockNewsDto news) {
        String text = ((news.getTitle() == null ? "" : news.getTitle()) + " "
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
     * 미국 대형주 후보에 Alpha Vantage 종목별 감성 및 Yahoo 컨센서스를 결합한다.
     * 긍정 감성(+0.25 이상)·관련도 높은 기사가 3건 이상인 경우만 촉매가 확인된 후보로
     * 반환한다. 무료 Alpha Vantage 한도를 고려해 상위 12개만 조회하고 30분 캐시한다.
     */
    public List<UsCandidateSignal> getUsCandidatesWithSignals(int limit) {
        if (limit <= 0) return List.of();
        if (isUsFresh()) return usCache.stream().limit(limit).toList();
        synchronized (this) {
            if (!isUsFresh()) {
                usCache = collectUsCandidates();
                usCacheTime = System.currentTimeMillis();
            }
        }
        return usCache.stream().limit(limit).toList();
    }

    private List<UsCandidateSignal> collectUsCandidates() {
        List<UsCandidateSignal> result = new ArrayList<>();
        for (YahooFinanceService.RawQuote quote : yahooFinanceService.fetchTopMarketCapUs(12)) {
            if (quote.price() <= 0 || quote.changePercent() <= 0) continue;
            List<StockNewsDto> positive = stockSymbolNewsService
                    .fetchForSymbol(quote.symbol(), "US", quote.name(), quote.name())
                    .stream()
                    .filter(news -> news.getSentimentScore() != null && news.getRelevanceScore() != null)
                    .filter(news -> news.getSentimentScore() >= 0.25 && news.getRelevanceScore() >= 0.5)
                    .limit(5)
                    .toList();
            if (positive.size() < 3) continue;

            double averageSentiment = positive.stream()
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
