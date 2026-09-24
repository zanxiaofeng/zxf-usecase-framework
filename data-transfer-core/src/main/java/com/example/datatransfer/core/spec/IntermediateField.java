package com.example.datatransfer.core.spec;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 中间结果声明（设计文档 §6.8）：在 rules 之前、源 + 已产出暂存的合并上下文上顺序求值，
 * 写入 {@code $} 前缀的独立命名空间——暂存键不进入最终输出。
 *
 * <p>两种形态二选一（引擎构造期校验，Schema oneOf 同约束）：</p>
 * <ul>
 *   <li>{@code from + transform}：从源/已产出暂存取字面路径值，可选变换链（复用现有函数）；
 *       from 不支持 {@code [*]}（数组派生用 expr 形态整体取值）；</li>
 *   <li>{@code expr}：JEXL 表达式，可跨字段组合/聚合，整体结果作为暂存值（可为容器）。</li>
 * </ul>
 *
 * <p>引用约束：本条只能引用<b>更早声明</b>的暂存键（前向引用，声明序即 DAG——装配期
 * fail-fast）；rules 的 {@code from}、transform 参数（{@code multiply($rate)}）与
 * computed 的 {@code expr} 均可引用暂存键。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntermediateField {

    /** 暂存键，{@code $} 前缀强制；纯点路径（不含 [*] 与 [n]），如 {@code $discountRate}、{@code $buyer.address} */
    @NotBlank
    private String to;

    /** 形态一：源或已产出暂存的字面路径（与 expr 互斥） */
    private String from;

    /** 形态一可选：{@code |} 分隔变换链，语义同 MappingRule.transform */
    private String transform;

    /** 形态二：JEXL 表达式，上下文为源 + 已产出暂存（与 from 互斥） */
    private String expr;
}
