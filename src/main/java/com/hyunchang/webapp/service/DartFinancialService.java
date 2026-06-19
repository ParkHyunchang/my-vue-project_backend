package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.Year;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipInputStream;

/**
 * OpenDART(금융감독원 전자공시) 재무제표 연동 — 국내 종목 재무 요약을 만든다.
 *
 *  1) corpCode.xml(zip) 으로 6자리 종목코드 → corp_code 매핑을 1회 로드/캐시
 *  2) fnlttSinglAcnt.json 으로 주요계정(매출/영업이익/순이익/자산·부채·자본) 조회
 *  3) 핵심 비율(영업이익률·순이익률·ROE·부채비율) 계산 후 한국어 블록 반환
 *
 * DART_API_KEY(application.yml dart.api-key)가 없으면 비활성(enabled()=false) → null 반환.
 * 모든 실패는 null 로 흡수해 분석이 멈추지 않게 한다.
 */
@Service
public class DartFinancialService {

    private static final Logger log = LoggerFactory.getLogger(DartFinancialService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final KrxOpenApiService krxOpenApiService;

    @Value("${dart.api-key:}")
    private String apiKey;

    /** 6자리 종목코드 → corp_code (8자리). 최초 1회 로드. */
    private volatile Map<String, String> stockToCorp = null;
    private final Object loadLock = new Object();

    public DartFinancialService(RestTemplate restTemplate,
                                ObjectMapper objectMapper,
                                KrxOpenApiService krxOpenApiService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.krxOpenApiService = krxOpenApiService;
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 국내 종목 재무 요약. symbol 은 "005930.KS" 또는 "005930". 미설정/실패 시 null. */
    public String summary(String symbol) {
        if (!enabled()) return null;
        String code = stockCode(symbol);
        if (code == null) return null;
        String corp = corpCode(code);
        if (corp == null) return null;

        int thisYear = Year.now().getValue();
        long marketCap = safeMarketCap(symbol);
        for (int y = thisYear - 1; y >= thisYear - 2; y--) {
            String block = fetchYear(corp, y, marketCap);
            if (block != null) return block;
        }
        return null;
    }

    // ── corp_code 매핑 ─────────────────────────────────────────────────────

    private String corpCode(String stockCode) {
        ensureCorpMap();
        return stockToCorp == null ? null : stockToCorp.get(stockCode);
    }

    private void ensureCorpMap() {
        if (stockToCorp != null) return;
        synchronized (loadLock) {
            if (stockToCorp != null) return;
            try {
                byte[] zip = restTemplate.getForObject(
                    "https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key=" + apiKey, byte[].class);
                if (zip == null || zip.length == 0) {
                    log.warn("[DART] corpCode 응답 비어있음");
                    return; // null 유지 → 다음 호출 때 재시도
                }
                Map<String, String> map = parseCorpZip(zip);
                if (!map.isEmpty()) {
                    stockToCorp = map;
                    log.info("[DART] corpCode 매핑 로드 완료: {}건", map.size());
                } else {
                    log.warn("[DART] corpCode 파싱 결과 0건 (키 오류 가능)");
                }
            } catch (Exception e) {
                log.warn("[DART] corpCode 로드 실패: {}", e.getMessage());
            }
        }
    }

    private Map<String, String> parseCorpZip(byte[] zipBytes) throws Exception {
        Map<String, String> map = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            if (zis.getNextEntry() == null) return map;
            byte[] xml = zis.readAllBytes();

            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList list = doc.getElementsByTagName("list");
            for (int i = 0; i < list.getLength(); i++) {
                Element el = (Element) list.item(i);
                String corp = text(el, "corp_code");
                String stock = text(el, "stock_code");
                if (corp != null && stock != null && stock.matches("\\d{6}")) {
                    map.put(stock, corp);
                }
            }
        }
        return map;
    }

    private String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        String v = nl.item(0).getTextContent();
        return v == null ? null : v.trim();
    }

    // ── 재무제표 조회 ──────────────────────────────────────────────────────

    /** 특정 사업연도(reprt_code=11011 사업보고서)의 주요계정으로 요약 블록 생성. 없으면 null. */
    private String fetchYear(String corp, int year, long marketCap) {
        try {
            String url = "https://opendart.fss.or.kr/api/fnlttSinglAcnt.json"
                + "?crtfc_key=" + apiKey
                + "&corp_code=" + corp
                + "&bsns_year=" + year
                + "&reprt_code=11011";
            String body = restTemplate.getForObject(url, String.class);
            if (body == null) return null;
            JsonNode root = objectMapper.readTree(body);
            if (!"000".equals(root.path("status").asText())) return null; // 013=데이터 없음 등

            // 연결(CFS) 우선, 없으면 개별(OFS)
            Map<String, Long> cfs = new HashMap<>();
            Map<String, Long> ofs = new HashMap<>();
            for (JsonNode item : root.path("list")) {
                String acc = item.path("account_nm").asText("");
                long amount = parseAmount(item.path("thstrm_amount").asText(""));
                if (amount == Long.MIN_VALUE) continue;
                Map<String, Long> target = "CFS".equals(item.path("fs_div").asText()) ? cfs : ofs;
                target.putIfAbsent(acc, amount);
            }
            Map<String, Long> fs = !cfs.isEmpty() ? cfs : ofs;
            if (fs.isEmpty()) return null;

            Long revenue = pick(fs, "매출액", "수익(매출액)", "영업수익");
            Long opProfit = pick(fs, "영업이익", "영업이익(손실)");
            Long netProfit = pick(fs, "당기순이익", "당기순이익(손실)", "당기순이익(손실)");
            Long assets = pick(fs, "자산총계");
            Long liabilities = pick(fs, "부채총계");
            Long equity = pick(fs, "자본총계");

            StringBuilder sb = new StringBuilder();
            line(sb, "매출액", revenue);
            if (opProfit != null) {
                sb.append("영업이익: ").append(won(opProfit));
                if (revenue != null && revenue != 0) sb.append(" (영업이익률 ").append(pct(opProfit, revenue)).append(")");
                sb.append("\n");
            }
            if (netProfit != null) {
                sb.append("당기순이익: ").append(won(netProfit));
                if (revenue != null && revenue != 0) sb.append(" (순이익률 ").append(pct(netProfit, revenue)).append(")");
                sb.append("\n");
            }
            line(sb, "자산총계", assets);
            line(sb, "부채총계", liabilities);
            line(sb, "자본총계", equity);
            if (marketCap > 0) {
                sb.append("시가총액(KRX): ").append(won(marketCap)).append("\n");
            }
            if (marketCap > 0 && netProfit != null) {
                if (netProfit > 0) {
                    sb.append("PER(주가수익비율, KRX 시총/DART 순이익): ")
                            .append(ratio(marketCap, netProfit)).append("배\n");
                } else {
                    sb.append("PER(주가수익비율): 산출 불가(당기순이익 적자 또는 0)\n");
                }
            }
            if (marketCap > 0 && equity != null) {
                if (equity > 0) {
                    sb.append("PBR(주가순자산비율, KRX 시총/DART 자본총계): ")
                            .append(ratio(marketCap, equity)).append("배\n");
                } else {
                    sb.append("PBR(주가순자산비율): 산출 불가(자본총계 0 이하)\n");
                }
            }
            if (liabilities != null && equity != null && equity != 0) {
                sb.append("부채비율: ").append(pct(liabilities, equity)).append("\n");
            }
            if (netProfit != null && equity != null && equity != 0) {
                sb.append("ROE(자기자본이익률): ").append(pct(netProfit, equity)).append("\n");
            }
            if (sb.length() == 0) return null;
            return "(출처: DART 전자공시, " + year + "년 사업보고서)\n" + sb.toString().strip();

        } catch (Exception e) {
            log.warn("[DART] {} {}년 재무 조회 실패: {}", corp, year, e.getMessage());
            return null;
        }
    }

    private Long pick(Map<String, Long> fs, String... names) {
        for (String n : names) {
            Long v = fs.get(n);
            if (v != null) return v;
        }
        return null;
    }

    /** "1,234,567" → long. 음수 괄호 "(1,234)" 처리. 실패 시 Long.MIN_VALUE. */
    private long parseAmount(String s) {
        if (s == null || s.isBlank() || "-".equals(s.trim())) return Long.MIN_VALUE;
        String t = s.trim();
        boolean neg = t.startsWith("(") && t.endsWith(")");
        t = t.replaceAll("[(),\\s]", "");
        if (t.isEmpty()) return Long.MIN_VALUE;
        try {
            long v = Long.parseLong(t);
            return neg ? -v : v;
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private void line(StringBuilder sb, String label, Long v) {
        if (v == null) return;
        sb.append(label).append(": ").append(won(v)).append("\n");
    }

    /** 원 단위 금액 → "1조 2,345억" 형태. */
    private String won(long v) {
        boolean neg = v < 0;
        long a = Math.abs(v);
        long jo = a / 1_0000_0000_0000L;
        long eok = (a % 1_0000_0000_0000L) / 1_0000_0000L;
        StringBuilder sb = new StringBuilder();
        if (jo > 0) sb.append(jo).append("조");
        if (eok > 0) sb.append(jo > 0 ? " " : "").append(String.format(Locale.KOREA, "%,d", eok)).append("억");
        if (sb.length() == 0) sb.append(String.format(Locale.KOREA, "%,d", a)).append("원");
        return (neg ? "-" : "") + sb;
    }

    private String pct(long part, long base) {
        return String.format(Locale.US, "%.1f%%", (double) part / base * 100.0);
    }

    private String ratio(long numerator, long denominator) {
        return String.format(Locale.US, "%.2f", (double) numerator / denominator);
    }

    private long safeMarketCap(String symbol) {
        try {
            return krxOpenApiService.getKrMarketCap(symbol);
        } catch (Exception e) {
            log.warn("[DART] KRX 시가총액 조회 실패 [{}]: {}", symbol, e.getMessage());
            return 0;
        }
    }

    private String stockCode(String symbol) {
        if (symbol == null) return null;
        String s = symbol.split("\\.")[0].trim();
        return s.matches("\\d{6}") ? s : null;
    }
}
