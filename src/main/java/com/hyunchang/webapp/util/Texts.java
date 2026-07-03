package com.hyunchang.webapp.util;

public final class Texts {
    private Texts() {}

    public static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public static String cleanAiReport(String text) {
        if (text == null) return "";
        String t = text.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.strip();
        }
        return t;
    }
}
