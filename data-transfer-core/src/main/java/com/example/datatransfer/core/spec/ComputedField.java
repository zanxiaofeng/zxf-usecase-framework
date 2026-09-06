package com.example.datatransfer.core.spec;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 计算字段（设计文档 §6.2）：在目标 FlatMap 上求值，表达式引用目标路径，聚合函数配合 [*] 使用。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComputedField {

    @NotBlank
    private String to;

    /** 如 {@code sum(crmOrder.lines[*].unitPrice * crmOrder.lines[*].quantity)} */
    @NotBlank
    private String expr;
}
