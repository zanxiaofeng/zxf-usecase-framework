package com.example.datatransfer.core.spec;

import java.util.List;

import lombok.Data;

/** 可观测性配置（设计文档 §11.2；第一版仅模型承载，指标埋点未实现）。 */
@Data
public class ObservabilityConfig {

    private Boolean tracing;

    private Boolean auditLog;

    private List<String> metrics;
}
