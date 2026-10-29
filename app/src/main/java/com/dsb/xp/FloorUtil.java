package com.dsb.xp;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从地址里解析楼层、解析「1-3,6」这种楼层集合。逻辑与旧版一致。 */
final class FloorUtil {
    private static final Pattern P_LOU = Pattern.compile("(\\d{1,2})\\s*(?:楼|层|樓|F\\b|f\\b)");
    private static final Pattern P_ROOM = Pattern.compile("(?<!\\d)(\\d{3,4})(?!\\d)");
    private static final Pattern P_NUM = Pattern.compile("(?<!\\d)(\\d{1,2})(?!\\d)");

    private FloorUtil() {}

    static Integer floor(String text, String dormName) {
        if (text == null || text.length() == 0) return null;
        if (dormName != null && dormName.length() > 0) text = text.replace(dormName, " ");

        Matcher m = P_LOU.matcher(text);
        if (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                if (n > 0 && n <= 60) return n;
            } catch (Exception ignored) {
            }
        }

        m = P_ROOM.matcher(text);
        String last = null;
        while (m.find()) last = m.group(1);
        if (last != null) {
            try {
                int n = last.length() == 3
                        ? Integer.parseInt(last.substring(0, 1))
                        : Integer.parseInt(last.substring(0, last.length() - 2));
                if (n > 0 && n <= 60) return n;
            } catch (Exception ignored) {
            }
        }

        m = P_NUM.matcher(text);
        last = null;
        while (m.find()) last = m.group(1);
        if (last != null) {
            try {
                int n = Integer.parseInt(last);
                if (n > 0 && n <= 60) return n;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** "1-3,6" -> {1,2,3,6}；空串返回空集合（表示不限楼层）。 */
    static Set<Integer> parseSet(String s) {
        Set<Integer> out = new LinkedHashSet<Integer>();
        if (s == null || s.trim().length() == 0) return out;
        String[] parts = s.replace("，", ",").split(",");
        for (String raw : parts) {
            String tok = raw.trim();
            if (tok.length() == 0) continue;
            try {
                int dash = tok.indexOf('-');
                if (dash > 0) {
                    int a = Integer.parseInt(tok.substring(0, dash).trim());
                    int b = Integer.parseInt(tok.substring(dash + 1).trim());
                    if (a > b) {
                        int t = a;
                        a = b;
                        b = t;
                    }
                    for (int i = a; i <= b && i - a < 200; i++) out.add(i);
                } else {
                    out.add(Integer.parseInt(tok));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
