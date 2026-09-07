package com.example.datatransfer.test;

/** 单条差异（设计文档 §10.6）：缺失 / 不匹配 / 多余。 */
public record DiffEntry(String path, DiffType type, Object expected, Object actual) {

    public static DiffEntry missing(String path, Object expected) {
        return new DiffEntry(path, DiffType.MISSING, expected, null);
    }

    public static DiffEntry mismatch(String path, Object expected, Object actual) {
        return new DiffEntry(path, DiffType.MISMATCH, expected, actual);
    }

    public static DiffEntry unexpected(String path, Object actual) {
        return new DiffEntry(path, DiffType.UNEXPECTED, null, actual);
    }
}
