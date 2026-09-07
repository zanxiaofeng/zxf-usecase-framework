package com.example.datatransfer.core.spec;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单字段断言（评审 6.2）：{@code assert} 为内置谓词 DSL——
 * {@code gt(n)} / {@code gte(n)} / {@code lt(n)} / {@code lte(n)} / {@code eq(v)} / {@code neq(v)}
 * （数值比较走 BigDecimal）、{@code regex('pattern')}、{@code notNull} / {@code notBlank}。
 *
 * <p>参数引号可省（数值）或单/双引号（字符串/正则）；{@code fn:} 自定义断言第一版不做
 * ——复杂判断走同级的 {@code condition}（JEXL，表达力等价且已过沙箱加固）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Assertion {

    /** 断言表达式，如 {@code gt(0)}、{@code regex('^\w+$')}、{@code notNull}（YAML 键名 assert） */
    @NotBlank
    @JsonProperty("assert")
    private String assertExpr;

    @NotBlank
    private String message;
}
