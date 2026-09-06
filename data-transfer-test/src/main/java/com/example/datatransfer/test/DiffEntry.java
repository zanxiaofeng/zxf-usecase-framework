package com.example.datatransfer.test;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 单条差异（设计文档 §10.6）：缺失 / 不匹配 / 多余。 */
@Data
@AllArgsConstructor(staticName = "of")
public class DiffEntry {

    private final String path;
    private final DiffType type;
    private final Object expected;
    private final Object actual;

    public static DiffEntry missing(String path, Object expected) {
        return of(path, DiffType.MISSING, expected, null);
    }

    public static DiffEntry mismatch(String path, Object expected, Object actual) {
        return of(path, DiffType.MISMATCH, expected, actual);
    }

    public static DiffEntry unexpected(String path, Object actual) {
        return of(path, DiffType.UNEXPECTED, null, actual);
    }
}
