package com.example.datatransfer.core.spec;

import lombok.Data;

/**
 * 条件映射分支（设计文档 §6.3）。两种形态二选一（Schema 层 oneOf 约束）：
 * 命中分支 {@code condition + transform}（condition 可引用源 FlatMap 顶层键）；
 * 兜底分支 {@code otherwise}（变换链字符串）。
 */
@Data
public class ConditionMapping {

    private String condition;

    private String transform;

    private String otherwise;
}
