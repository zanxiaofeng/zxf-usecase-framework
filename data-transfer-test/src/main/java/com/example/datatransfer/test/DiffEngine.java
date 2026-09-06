package com.example.datatransfer.test;

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
            } else if (!valuesEqual(entry.getValue(), actual.get(path))) {
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
     * 数值宽松比较：113 与 113.00 等价（YAML 期望文件常把数字读成字符串，
     * 故对 String↔Number 做单向宽松化，仅放宽期望侧）。
     */
    private static boolean valuesEqual(Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            return true;
        }
        if (expected instanceof Number expNumber && actual instanceof Number actNumber) {
            return Double.compare(expNumber.doubleValue(), actNumber.doubleValue()) == 0;
        }
        if (expected instanceof String expString && actual instanceof Number actNumber) {
            try {
                return Double.parseDouble(expString) == actNumber.doubleValue();
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }
}
