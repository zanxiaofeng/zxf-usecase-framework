package com.example.datatransfer.test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.Data;

/** 差异汇总（设计文档 §10.6）。 */
@Data
public class DiffResult {

    private final List<DiffEntry> entries;

    public boolean isEmpty() {
        return entries == null || entries.isEmpty();
    }

    public int size() {
        return entries == null ? 0 : entries.size();
    }

    public Map<DiffType, Long> summary() {
        return entries.stream()
                .collect(Collectors.groupingBy(DiffEntry::getType, Collectors.counting()));
    }
}
