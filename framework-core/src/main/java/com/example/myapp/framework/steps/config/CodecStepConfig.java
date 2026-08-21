package com.example.myapp.framework.steps.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * encoder / decoder 步骤的 config schema。decoder 的算法可逆性依赖注册表类型
 * （{@code ReversibleCodec}），仍由 CodecStepFactory 在装配期校验。
 *
 * <p>@Data + 字段初始值模式：默认值直接写在字段上，Jackson 绑定时仅覆盖 YAML 中出现的属性。</p>
 */
@Data
public class CodecStepConfig {

    /** 算法名（必填），如 base64 / base64url / url / hex / md5 / sha256 */
    @NotBlank
    private String algorithm;

    /** 输入 SpEL 表达式，缺省 #payload */
    private String source = "#payload";

    /** 结果写入 #vars 的旁路键；缺省写回 payload */
    private @Nullable String as;
}
