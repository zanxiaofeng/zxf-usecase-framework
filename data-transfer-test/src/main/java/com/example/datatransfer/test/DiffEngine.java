package com.example.datatransfer.test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** 结构化差异比对（设计文档 §10.5）：期望 FlatMap vs 实际 FlatMap。 */
public final class DiffEngine {

    private DiffEngine() {
    }

    /** 严格 JSON 类型口径的比较：String 与 Number 是不同的值 */
    public static DiffResult diff(Map<String, Object> expected,
                                  Map<String, Object> actual,
                                  TransferAssert.CompareMode mode) {
        return diff(expected, actual, mode, false);
    }

    /**
     * @param coerceNumericStrings 是否把<b>期望侧</b>的数字字符串当作数值比较（仅放宽期望侧）。
     *        默认关闭：JSON 类型是契约的一部分，{@code "000123"} 与 {@code 123} 不等
     *        （外部评审二轮 P1-7：历史行为是单向宽松）。YAML / 手写期望常把数字写成字符串时显式开启。
     */
    public static DiffResult diff(Map<String, Object> expected,
                                  Map<String, Object> actual,
                                  TransferAssert.CompareMode mode,
                                  boolean coerceNumericStrings) {
        List<DiffEntry> entries = new ArrayList<>();

        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String path = entry.getKey();
            if (!actual.containsKey(path)) {
                entries.add(DiffEntry.missing(path, entry.getValue()));
                continue;
            }
            if (!looseEquals(entry.getValue(), actual.get(path), coerceNumericStrings)) {
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

    /** 数值跨形态等价（113 与 113.00），String 与 Number 不等价 */
    static boolean looseEquals(Object expected, @Nullable Object actual) {
        return looseEquals(expected, actual, false);
    }

    /**
     * 值等价判定：{@code Objects.equals} 之外只放宽「Number ↔ Number 按 BigDecimal compareTo」
     * （113 ≡ 113.00，超过 2^53 的大整数与长小数不经 double、不丢精度）；
     * {@code coerceNumericStrings} 为 true 时再放宽「期望侧 String ↔ 实际侧 Number」。
     */
    static boolean looseEquals(Object expected, @Nullable Object actual, boolean coerceNumericStrings) {
        if (Objects.equals(expected, actual)) {
            return true;
        }
        if (expected instanceof Number expNumber && actual instanceof Number actNumber) {
            return toDecimal(expNumber).compareTo(toDecimal(actNumber)) == 0;
        }
        if (coerceNumericStrings && expected instanceof String expString && actual instanceof Number actNumber) {
            try {
                return new BigDecimal(expString).compareTo(toDecimal(actNumber)) == 0;
            } catch (NumberFormatException notNumeric) {
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
