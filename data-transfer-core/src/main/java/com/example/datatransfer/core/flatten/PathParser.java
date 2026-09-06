package com.example.datatransfer.core.flatten;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FlatKey 路径解析（设计文档 §5.2）：{@code user.tags[0]} → {@code ["user", "tags", 0]}。
 *
 * <p>{@link #parsePattern} 保留通配哨兵 {@link #WILDCARD}，供引擎做规则与实际键的
 * 多级 {@code [*]} 对位（设计文档 §6.1）。</p>
 */
public final class PathParser {

    /** 通配哨兵：parsePattern 中 {@code [*]} 段的占位（String 类型，与索引段 Integer 区分） */
    public static final String WILDCARD = "[*]";

    private static final Pattern PLAIN_SEGMENT = Pattern.compile("([^\\[\\]]*)((?:\\[\\d+\\])*)");
    private static final Pattern WILDCARD_SEGMENT = Pattern.compile("([^\\[\\]]*)((?:\\[\\d+\\]|\\[\\*\\])*)");
    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)\\]");
    private static final Pattern WILDCARD_INDEX = Pattern.compile("\\[(\\d+|\\*)\\]");

    private PathParser() {
    }

    /** 实际 FlatKey（索引段为 Integer；不含通配符——出现即视为非法） */
    public static List<Object> parse(String path, String separator) {
        return parseInternal(path, separator, PLAIN_SEGMENT, INDEX, false);
    }

    /** 规则路径：{@code [*]} 段保留为 {@link #WILDCARD} 哨兵 */
    public static List<Object> parsePattern(String path, String separator) {
        return parseInternal(path, separator, WILDCARD_SEGMENT, WILDCARD_INDEX, true);
    }

    /** 段列表重建为 FlatKey 字符串：identifier 段间以 separator 连接，索引/通配段直接拼接 */
    public static String toPath(List<Object> segments, String separator) {
        StringBuilder sb = new StringBuilder();
        for (Object seg : segments) {
            if (seg instanceof Integer idx) {
                sb.append('[').append(idx).append(']');
            } else if (WILDCARD.equals(seg)) {
                sb.append(WILDCARD);
            } else {
                if (!sb.isEmpty()) {
                    sb.append(separator);
                }
                sb.append(seg);
            }
        }
        return sb.toString();
    }

    private static List<Object> parseInternal(String path, String separator, Pattern segmentPattern,
                                               Pattern indexPattern, boolean keepWildcard) {
        List<Object> segments = new ArrayList<>();
        for (String part : path.split(Pattern.quote(separator), -1)) {
            Matcher m = segmentPattern.matcher(part);
            if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) {
                throw new IllegalArgumentException("invalid flat key segment: " + part + " (in " + path + ")");
            }
            if (!m.group(1).isEmpty()) {
                segments.add(m.group(1));
            }
            Matcher idx = indexPattern.matcher(m.group(2));
            while (idx.find()) {
                segments.add(keepWildcard && idx.group(1).equals("*") ? WILDCARD : Integer.valueOf(idx.group(1)));
            }
        }
        return segments;
    }
}
