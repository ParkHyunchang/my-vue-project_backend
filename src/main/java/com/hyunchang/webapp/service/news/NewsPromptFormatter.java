package com.hyunchang.webapp.service.news;

import com.hyunchang.webapp.dto.StockNewsDto;
import com.hyunchang.webapp.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class NewsPromptFormatter {
    private NewsPromptFormatter() {}

    public static String format(StockNewsDto news, int descLimit) {
        if (news == null || Texts.nullSafe(news.getTitle()).isBlank()) return "";
        List<String> meta = new ArrayList<>();
        if (!Texts.nullSafe(news.getSource()).isBlank()) meta.add("출처: " + news.getSource().trim());
        if (!Texts.nullSafe(news.getPubDate()).isBlank())
            meta.add("날짜: " + news.getPubDate().trim());

        StringBuilder sb = new StringBuilder();
        if (!meta.isEmpty()) {
            sb.append("[").append(String.join(", ", meta)).append("] ");
        }
        sb.append(news.getTitle().trim());
        if (!Texts.nullSafe(news.getDescription()).isBlank()) {
            sb.append(" — 요약: ").append(Texts.truncate(news.getDescription().trim(), descLimit));
        }
        return sb.toString();
    }

    public static String dedupeKey(StockNewsDto news) {
        if (news == null) return "";
        if (Texts.notBlank(news.getLink())) return news.getLink().trim().toLowerCase(Locale.ROOT);
        if (Texts.notBlank(news.getTitle())) return news.getTitle().trim().toLowerCase(Locale.ROOT);
        return "";
    }
}
