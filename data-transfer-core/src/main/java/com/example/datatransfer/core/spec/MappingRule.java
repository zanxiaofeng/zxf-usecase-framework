package com.example.datatransfer.core.spec;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条映射规则（设计文档 §2.3）：from → to 的数据搬运 + 变换。
 *
 * <p>{@code transform} 为 {@code |} 分隔的链式调用（如 {@code trim | multiply(1.13) | round(2)}）；
 * {@code when} 与 {@code transform} 互斥——前者按条件分支选择变换链。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MappingRule {

    @NotBlank
    private String from;

    @NotBlank
    private String to;

    private String transform;

    /** 条件映射（设计文档 §6.3）：condition 命中的分支走其 transform，否则走 otherwise */
    private List<ConditionMapping> when;
}
