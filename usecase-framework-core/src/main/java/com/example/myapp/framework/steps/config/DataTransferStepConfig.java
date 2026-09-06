package com.example.myapp.framework.steps.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * dataTransfer step 配置：引用 classpath 上的 TransferSpec 文件做声明式数据搬运
 * （设计文档见 docs/基于JSON Flatten的声明式Data Transfer框架.md §8 集成形态）。
 *
 * <p>spec 即合同文件，可直接被 data-transfer-test 的
 * {@code TransferAssert.assertThat("transfers/xxx.yaml")} 契约测试断言。</p>
 */
@Data
@SuppressWarnings("NullAway.Init")   // 字段由 StepConfigs 绑定填充，非空约束由 Bean Validation 绑定后承担
public class DataTransferStepConfig {

    /** TransferSpec 的 classpath 位置（可带 classpath: 前缀），如 transfers/user-to-crm.yaml */
    @NotBlank
    private String spec;

    /** 取输入的 SpEL 表达式，缺省整个 payload */
    private String source = "#payload";

    /** 旁路输出键（写 vars）；缺省覆盖 payload */
    @Pattern(regexp = "\\S+", message = "must not be blank when set")
    private @Nullable String as;
}
