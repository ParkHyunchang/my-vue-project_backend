package com.hyunchang.webapp.service.news;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class RssClient {
    private static final DateTimeFormatter RFC_822 =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final RestTemplate restTemplate;

    public RssClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public record RssItem(
        String title,
        String link,
        String description,
        String pubDate,
        String itemSource,
        String imageUrl
    ) {}

    public List<RssItem> fetchItems(String url) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (compatible; RSS Reader/1.0)");
        ResponseEntity<String> resp = restTemplate.exchange(
            url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        String xml = resp.getBody();
        if (xml == null || xml.isBlank()) return List.of();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        NodeList nodes = doc.getElementsByTagName("item");
        List<RssItem> items = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            Element el = (Element) nodes.item(i);
            items.add(new RssItem(
                textOf(el, "title"),
                textOf(el, "link"),
                textOf(el, "description"),
                textOf(el, "pubDate"),
                textOf(el, "source"),
                imageUrlOf(el)
            ));
        }
        return items;
    }

    public static long parsePubDateEpoch(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return 0L;
        String normalized = pubDate.trim();
        try {
            return ZonedDateTime.parse(normalized, RFC_822).toEpochSecond();
        } catch (Exception ignored) {
            try {
                return ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
            } catch (Exception ignoredAgain) {
                return 0L;
            }
        }
    }

    public static String mergeSource(String sourceName, String itemSource) {
        if (itemSource == null || itemSource.isBlank()) return sourceName;
        if (sourceName == null || sourceName.isBlank()) return itemSource;
        if (sourceName.toLowerCase(Locale.ROOT).contains(itemSource.toLowerCase(Locale.ROOT))) {
            return sourceName;
        }
        return sourceName + " · " + itemSource;
    }

    public static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "");
    }

    private static String textOf(Element el, String tagName) {
        NodeList nodes = el.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return "";
        String txt = nodes.item(0).getTextContent();
        return txt == null ? "" : txt.trim();
    }

    private static String imageUrlOf(Element el) {
        NodeList mc = el.getElementsByTagName("media:content");
        for (int i = 0; i < mc.getLength(); i++) {
            if (mc.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            Element e = (Element) mc.item(i);
            String urlAttr = e.getAttribute("url");
            String medium = e.getAttribute("medium");
            String type = e.getAttribute("type");
            if (!urlAttr.isBlank() && (medium.isBlank() || "image".equals(medium)
                    || type.startsWith("image/"))) {
                return urlAttr;
            }
        }

        NodeList mt = el.getElementsByTagName("media:thumbnail");
        if (mt.getLength() > 0 && mt.item(0).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
            String urlAttr = ((Element) mt.item(0)).getAttribute("url");
            if (!urlAttr.isBlank()) return urlAttr;
        }

        NodeList enc = el.getElementsByTagName("enclosure");
        for (int i = 0; i < enc.getLength(); i++) {
            if (enc.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            Element e = (Element) enc.item(i);
            if (e.getAttribute("type").startsWith("image/")) return e.getAttribute("url");
        }
        return null;
    }
}
