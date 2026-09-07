package com.example.datatransfer.core.spec;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 转换过程中的数据值校验（评审 6.1/6.2）：对<b>目标 FlatMap</b>（转换后的值）执行，
 * 时点在 rules + computed + defaults 全部完成后、Unflatten 之前。
 *
 * <p>两种形态二选一：{@code rules}（单字段断言，path 可含 {@code [*]} 逐元素断言）或
 * {@code condition}（JEXL 组合条件——path 为元素路径时，condition 中的裸标识符解析为
 * 该元素的字段，如 {@code unitPrice > 0 && quantity > 0}）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationRule {

    /** 校验路径（目标 FlatMap 键格式，可含 [*]） */
    @NotBlank
    private String path;

    /** 单字段断言列表（与 condition 二选一） */
    private List<Assertion> rules;

    /** JEXL 组合条件（与 rules 二选一）；标量 path 下可用 {@code value} 引用当前值 */
    private String condition;

    /** condition 形态的失败消息（必填）；rules 形态用各断言内的 message（此处可空） */
    private String message;
}
