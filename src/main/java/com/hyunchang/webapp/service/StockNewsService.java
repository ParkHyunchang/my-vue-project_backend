package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.StockNewsDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 주식 뉴스 서비스.
 * - 국내/해외 RSS 피드 수집 및 파싱
 * - 해외 뉴스 한글 자동 번역 (Google Translate 비공식 API)
 * - 20분 인메모리 캐시
 */
@Service
public class StockNewsService {

    private static final Logger log = LoggerFactory.getLogger(StockNewsService.class);
    private static final long NEWS_CACHE_TTL_MS = 20 * 60 * 1000L; // 20분

    // 뉴스 RSS 소스 — {출처명, URL, 시장(KR|US)}
    private static final List<String[]> RSS_SOURCES = List.of(
        new String[]{"한국경제",      "https://www.hankyung.com/feed/finance",                                              "KR"},
        new String[]{"머니투데이",    "https://news.mt.co.kr/newsRSS.php?cast=1&SECTION_CD=SC1",                            "KR"},
        new String[]{"연합뉴스",      "https://www.yna.co.kr/rss/economy.xml",                                             "KR"},
        new String[]{"Yahoo Finance", "https://finance.yahoo.com/rss/topfinstories",                                        "US"},
        new String[]{"MarketWatch",   "https://feeds.marketwatch.com/marketwatch/marketpulse/",                              "US"},
        new String[]{"CNBC",          "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=15839069", "US"}
    );

    // 뉴스 캐시
    private volatile List<StockNewsDto> krNewsCache     = null;
    private volatile long               krNewsCacheTime = 0;
    private volatile List<StockNewsDto> usNewsCache     = null;
    private volatile long               usNewsCacheTime = 0;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public StockNewsService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper  = objectMapper;
    }

    /**
     * 시장별 뉴스 조회. market: "KR" | "US" | 그 외(전체).
     * 캐시 TTL(20분) 이내면 캐시 반환, 만료 시 RSS 재수집.
     */
    public List<StockNewsDto> getNews(String market) {
        long now = System.currentTimeMillis();

        if ("KR".equalsIgnoreCase(market)) {
            if (krNewsCache != null && (now - krNewsCacheTime) < NEWS_CACHE_TTL_MS) {
                log.info("뉴스 캐시 히트 [KR]");
                return krNewsCache;
            }
            List<StockNewsDto> fresh = fetchByMarket("KR");
            krNewsCache     = fresh;
            krNewsCacheTime = now;
            return fresh;
        }

        if ("US".equalsIgnoreCase(market)) {
            if (usNewsCache != null && (now - usNewsCacheTime) < NEWS_CACHE_TTL_MS) {
                log.info("뉴스 캐시 히트 [US]");
                return usNewsCache;
            }
            List<StockNewsDto> fresh     = fetchByMarket("US");
            List<StockNewsDto> translated = fresh.stream().map(n -> StockNewsDto.builder()
                    .title(translateToKorean(n.getTitle()))
                    .link(n.getLink())
                    .description(n.getDescription() != null ? translateToKorean(n.getDescription()) : null)
                    .pubDate(n.getPubDate())
                    .source(n.getSource())
                    .market(n.getMarket())
                    .build()).toList();
            usNewsCache     = translated;
            usNewsCacheTime = now;
            return translated;
        }

        // ALL — 두 시장 합산
        List<StockNewsDto> all = new ArrayList<>(getNews("KR"));
        all.addAll(getNews("US"));
        return all.stream().limit(30).toList();
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 메서드
    // ─────────────────────────────────────────────────────────────

    private List<StockNewsDto> fetchByMarket(String market) {
        List<StockNewsDto> all = new ArrayList<>();
        for (String[] src : RSS_SOURCES) {
            if (!src[2].equalsIgnoreCase(market)) continue;
            try { all.addAll(parseRss(src[1], src[0], src[2])); }
            catch (Exception e) { log.warn("RSS 파싱 실패 [{}]: {}", src[0], e.getMessage()); }
        }
        return all.stream().limit(30).toList();
    }

    private List<StockNewsDto> parseRss(String url, String sourceName, String market) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (compatible; RSS Reader/1.0)");
        ResponseEntity<String> resp = restTemplate.exchange(
            url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        String xml = resp.getBody();
        if (xml == null || xml.isBlank()) return Collections.emptyList();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        NodeList items = doc.getElementsByTagName("item");
        List<StockNewsDto> news = new ArrayList<>();
        int limit = Math.min(items.getLength(), 10);

        for (int i = 0; i < limit; i++) {
            if (items.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            Element el      = (Element) items.item(i);
            String  title   = getNodeText(el, "title");
            String  link    = getNodeText(el, "link");
            String  desc    = getNodeText(el, "description");
            String  pubDate = getNodeText(el, "pubDate");

            if (title.isBlank()) continue;
            desc = desc.replaceAll("<[^>]*>", "").trim();
            if (desc.length() > 120) desc = desc.substring(0, 120) + "…";

            news.add(StockNewsDto.builder()
                .title(title).link(link).description(desc)
                .pubDate(pubDate).source(sourceName).market(market).build());
        }
        return news;
    }

    private String translateToKorean(String text) {
        if (text == null || text.isBlank()) return text;
        try {
            String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String url = "https://translate.googleapis.com/translate_a/single"
                + "?client=gtx&sl=en&tl=ko&dt=t&q=" + encoded;
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0");
            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode parts = objectMapper.readTree(resp.getBody()).get(0);
            if (parts == null || !parts.isArray()) return text;
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.isArray() && !part.isEmpty()) sb.append(part.get(0).asText(""));
            }
            String result = sb.toString().trim();
            if (result.isEmpty()) return text;
            try { result = URLDecoder.decode(result, StandardCharsets.UTF_8); }
            catch (Exception ignore) {}
            return result;
        } catch (Exception e) {
            log.warn("번역 실패: {}", e.getMessage());
            return text;
        }
    }

    private String getNodeText(Element el, String tagName) {
        NodeList nodes = el.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return "";
        return nodes.item(0).getTextContent().trim();
    }
}
