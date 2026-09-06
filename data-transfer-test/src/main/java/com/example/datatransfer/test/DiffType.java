package com.example.datatransfer.test;

/** 差异类型（设计文档 §10.6）。 */
public enum DiffType {
    MISSING("缺失"),
    MISMATCH("不匹配"),
    UNEXPECTED("多余");

    private final String label;

    DiffType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
