package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.RealEstateNewsDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 부동산 뉴스 RSS 수집 서비스 (국내 부동산 전용 피드).
 * 20분 TTL 캐시, 소스당 최대 15건, 총 40건 제한. 주식 뉴스(StockNewsService) 패턴 차용.
 */
@Service
public class RealEstateNewsService {

    private static final Logger log = LoggerFactory.getLogger(RealEstateNewsService.class);

    private record Feed(String name, String url) {}

    private static final List<Feed> FEEDS = List.of(
        new Feed("한국경제", "https://www.hankyung.com/feed/realestate"),
        new Feed("매일경제", "https://www.mk.co.kr/rss/50300009/")
    );

    private static final int  PER_SOURCE_LIMIT = 15;
    private static final int  TOTAL_LIMIT      = 40;
    private static final int  MAX_AGE_DAYS     = 14;
    private static final long CACHE_TTL_MS     = 20 * 60 * 1000L;
    private static final DateTimeFormatter RSS_DATE_FMT = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final Pattern IMG_PATTERN = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']");

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private volatile List<RealEstateNewsDto> cache = Collections.emptyList();
    private volatile long cacheTime = 0;

    public List<RealEstateNewsDto> getNews(boolean force) {
        if (!force && !cache.isEmpty() && (System.currentTimeMillis() - cacheTime) < CACHE_TTL_MS) {
            return cache;
        }
        List<RealEstateNewsDto> all = new ArrayList<>();
        for (Feed feed : FEEDS) {
            try {
                all.addAll(parseRss(feed));
            } catch (Exception e) {
                log.warn("부동산 뉴스 RSS 파싱 실패 [{}]: {}", feed.name(), e.getMessage());
            }
        }
        if (all.size() > TOTAL_LIMIT) all = all.subList(0, TOTAL_LIMIT);
        if (!all.isEmpty()) {
            cache = all;
            cacheTime = System.currentTimeMillis();
        }
        return all.isEmpty() ? cache : all;
    }

    private List<RealEstateNewsDto> parseRss(Feed feed) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(feed.url()))
            .timeout(Duration.ofSeconds(12))
            .header("User-Agent", "Mozilla/5.0 (compatible; RSS Reader/1.0)")
            .GET()
            .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) {
            return Collections.emptyList();
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(resp.body())));

        NodeList items = doc.getElementsByTagName("item");
        List<RealEstateNewsDto> news = new ArrayList<>();
        long maxAgeSec = (long) MAX_AGE_DAYS * 24 * 3600;
        long nowEpoch  = System.currentTimeMillis() / 1000;

        for (int i = 0; i < items.getLength() && news.size() < PER_SOURCE_LIMIT; i++) {
            if (items.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            Element el = (Element) items.item(i);

            String title = getText(el, "title");
            String link  = getText(el, "link");
            String desc  = getText(el, "description");
            String pubDate = getText(el, "pubDate");
            if (title.isBlank()) continue;

            if (!pubDate.isBlank()) {
                try {
                    long pubEpoch = ZonedDateTime.parse(pubDate.trim(), RSS_DATE_FMT).toEpochSecond();
                    if ((nowEpoch - pubEpoch) > maxAgeSec) continue;
                } catch (Exception ignore) { /* 파싱 실패 시 통과 */ }
            }

            String imageUrl = extractImage(desc);
            String cleanDesc = desc.replaceAll("<[^>]*>", "").trim();
            if (cleanDesc.length() > 300) cleanDesc = cleanDesc.substring(0, 300) + "…";

            news.add(RealEstateNewsDto.builder()
                .title(title.trim())
                .link(link.trim())
                .description(cleanDesc)
                .pubDate(pubDate)
                .source(feed.name())
                .imageUrl(imageUrl)
                .build());
        }
        return news;
    }

    private String extractImage(String desc) {
        if (desc == null) return null;
        Matcher m = IMG_PATTERN.matcher(desc);
        return m.find() ? m.group(1) : null;
    }

    private String getText(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0 || nl.item(0).getTextContent() == null) return "";
        return nl.item(0).getTextContent();
    }
}
