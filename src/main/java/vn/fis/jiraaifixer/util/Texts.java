package vn.fis.jiraaifixer.util;

import tools.jackson.databind.JsonNode;

public final class Texts {

    private Texts() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String nz(String s) {
        return s == null ? "" : s;
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Đọc giá trị chuỗi theo đường dẫn field; null nếu thiếu hoặc null. */
    public static String text(JsonNode node, String... path) {
        JsonNode cur = node;
        for (String p : path) {
            if (cur == null) {
                return null;
            }
            cur = cur.path(p);
        }
        if (cur == null || cur.isMissingNode() || cur.isNull()) {
            return null;
        }
        return cur.isValueNode() ? cur.asString() : cur.toString();
    }
}
