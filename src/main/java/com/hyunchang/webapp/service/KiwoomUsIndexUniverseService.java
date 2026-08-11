package com.hyunchang.webapp.service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class KiwoomUsIndexUniverseService {
    private static final String SP500_URL =
            "https://en.wikipedia.org/wiki/List_of_S%26P_500_companies";
    private static final String NASDAQ100_URL =
            "https://en.wikipedia.org/wiki/List_of_NASDAQ-100_companies";
    private static final Duration MAX_CACHE_AGE = Duration.ofDays(7);
    private volatile Universe universe;
    private volatile String lastError = "아직 지수 구성종목을 불러오지 않았습니다.";

    @Scheduled(fixedDelay = 86_400_000, initialDelay = 5_000)
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException ignored) {
            // The last successful cache remains available. Candidate selection fails closed when
            // stale.
        }
    }

    public boolean isEligible(String symbol) {
        Universe current = current();
        return current.sp500().contains(normalize(symbol))
                || current.nasdaq100().contains(normalize(symbol));
    }

    public String membershipLabel(String symbol) {
        Universe current = current();
        String normalized = normalize(symbol);
        boolean sp500 = current.sp500().contains(normalized);
        boolean nasdaq100 = current.nasdaq100().contains(normalized);
        if (sp500 && nasdaq100) return "S&P 500 · NASDAQ-100";
        if (sp500) return "S&P 500";
        if (nasdaq100) return "NASDAQ-100";
        return "미편입";
    }

    public UniverseStatus status() {
        Universe current = universe;
        if (current == null) return new UniverseStatus(false, 0, 0, 0, null, lastError);
        boolean fresh =
                Duration.between(current.updatedAt(), LocalDateTime.now()).compareTo(MAX_CACHE_AGE)
                        <= 0;
        return new UniverseStatus(
                fresh,
                current.sp500().size(),
                current.nasdaq100().size(),
                current.unionSize(),
                current.updatedAt(),
                fresh ? "" : "지수 구성종목 캐시가 7일 이상 갱신되지 않았습니다. " + lastError);
    }

    public synchronized void refresh() {
        try {
            Set<String> sp500 = loadSymbols(SP500_URL, "Symbol", 450);
            Set<String> nasdaq100 = loadSymbols(NASDAQ100_URL, "Ticker", 90);
            Set<String> union = new HashSet<>(sp500);
            union.addAll(nasdaq100);
            universe =
                    new Universe(
                            Set.copyOf(sp500),
                            Set.copyOf(nasdaq100),
                            union.size(),
                            LocalDateTime.now());
            lastError = "";
        } catch (IOException | RuntimeException error) {
            lastError =
                    error.getMessage() == null
                            ? error.getClass().getSimpleName()
                            : error.getMessage();
            throw new IllegalStateException("미국 주요지수 구성종목 갱신 실패: " + lastError, error);
        }
    }

    private Universe current() {
        Universe current = universe;
        if (current == null) {
            refresh();
            current = universe;
        }
        if (current == null
                || Duration.between(current.updatedAt(), LocalDateTime.now())
                                .compareTo(MAX_CACHE_AGE)
                        > 0) {
            throw new IllegalStateException("S&P 500·NASDAQ-100 구성종목을 확인할 수 없어 신규 매수를 차단했습니다.");
        }
        return current;
    }

    private Set<String> loadSymbols(String url, String firstHeader, int minimumCount)
            throws IOException {
        Document document =
                Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 Kiwoom-US-index-universe/1.0")
                        .timeout(15_000)
                        .get();
        for (Element table : document.select("table")) {
            Element firstRow = table.selectFirst("tr");
            Element header = firstRow == null ? null : firstRow.selectFirst("th");
            if (header == null || !firstHeader.equalsIgnoreCase(header.text().trim())) continue;
            Set<String> symbols = new HashSet<>();
            for (Element row : table.select("tbody tr")) {
                Element cell = row.selectFirst("td");
                if (cell == null) continue;
                String symbol = normalize(cell.text());
                if (symbol.matches("[A-Z0-9.]{1,12}")) symbols.add(symbol);
            }
            if (symbols.size() < minimumCount) {
                throw new IllegalStateException(
                        firstHeader + " 지수 구성종목 수가 예상보다 적습니다: " + symbols.size());
            }
            return symbols;
        }
        throw new IllegalStateException(firstHeader + " 지수 구성종목 표를 찾지 못했습니다.");
    }

    private String normalize(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT).replace('-', '.');
    }

    private record Universe(
            Set<String> sp500, Set<String> nasdaq100, int unionSize, LocalDateTime updatedAt) {}

    public record UniverseStatus(
            boolean available,
            int sp500Count,
            int nasdaq100Count,
            int unionCount,
            LocalDateTime updatedAt,
            String message) {}
}
