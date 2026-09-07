package com.example.datatransfer.test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 差异汇总（设计文档 §10.6）。 */
public record DiffResult(List<DiffEntry> entries) {

    public DiffResult {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public Map<DiffType, Long> summary() {
        return entries.stream()
                .collect(Collectors.groupingBy(DiffEntry::type, Collectors.counting()));
    }
}
