package com.example.datatransfer.test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 结构化差异比对（设计文档 §10.5）：期望 FlatMap vs 实际 FlatMap。 */
public final class DiffEngine {

    private DiffEngine() {
    }

    public static DiffResult diff(Map<String, Object> expected,
                                  Map<String, Object> actual,
                                  TransferAssert.CompareMode mode) {
        List<DiffEntry> entries = new ArrayList<>();

        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String path = entry.getKey();
            if (!actual.containsKey(path)) {
                entries.add(DiffEntry.missing(path, entry.getValue()));
            } else if (!looseEquals(entry.getValue(), actual.get(path))) {
                entries.add(DiffEntry.mismatch(path, entry.getValue(), actual.get(path)));
            }
        }

        if (mode == TransferAssert.CompareMode.STRICT) {
            for (String path : actual.keySet()) {
                if (!expected.containsKey(path)) {
                    entries.add(DiffEntry.unexpected(path, actual.get(path)));
                }
            }
        }

        return new DiffResult(entries);
    }

    /**
     * 数值宽松比较（diff 与链式断言的统一口径）：113 与 113.00 等价——
     * BigDecimal {@code compareTo} 精确比较，超过 2^53 的大整数不经 double 中转、不丢精度；
     * YAML 期望文件常把数字读成字符串，故对 String↔Number 做单向宽松化（仅放宽期望侧）。
     */
    static boolean looseEquals(Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            return true;
        }
        if (expected instanceof Number expNumber && actual instanceof Number actNumber) {
            return toDecimal(expNumber).compareTo(toDecimal(actNumber)) == 0;
        }
        if (expected instanceof String expString && actual instanceof Number actNumber) {
            try {
                return new BigDecimal(expString).compareTo(toDecimal(actNumber)) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    /** Number → BigDecimal：走 {@code toString} 而非 {@code doubleValue}，浮点/大整数无损 */
    private static BigDecimal toDecimal(Number number) {
        return number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
    }
}
