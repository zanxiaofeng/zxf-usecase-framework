package com.example.datatransfer.core.spec;

/** null 值处置策略（设计文档 §2.4）：SKIP 跳过不写目标键 / KEEP 原样写入 / DEFAULT 交由变换链的 default() 兜底。 */
public enum NullPolicy {
    SKIP, KEEP, DEFAULT
}
