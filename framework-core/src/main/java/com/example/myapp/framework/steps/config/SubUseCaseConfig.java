package com.example.myapp.framework.steps.config;

import lombok.Data;

/**
 * usecase（子用例调用）步骤的 config schema。目标用例 id 在 step 的 ref 属性上
 * （存在性与环由 UseCaseAssembler 校验），不在本 config 内。
 *
 * <p>@Data + 字段初始值模式：默认值直接写在字段上，Jackson 绑定时仅覆盖 YAML 中出现的属性。</p>
 */
@Data
public class SubUseCaseConfig {

    /** 子用例入参 SpEL 表达式，缺省 #payload */
    private String input = "#payload";

    /** 结果写入 #vars 的旁路键；缺省写回 payload（串联模式） */
    private String as;

    /** true 时隔离上下文调用（子不污染父 vars/payload） */
    private boolean isolate;
}
