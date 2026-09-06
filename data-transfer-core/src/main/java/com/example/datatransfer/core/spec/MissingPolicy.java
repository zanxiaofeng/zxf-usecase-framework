package com.example.datatransfer.core.spec;

/** 规则未命中任何源键时的处置策略（设计文档 §2.4）：WARN 日志告警 / ERROR 抛异常 / IGNORE 静默。 */
public enum MissingPolicy {
    WARN, ERROR, IGNORE
}
