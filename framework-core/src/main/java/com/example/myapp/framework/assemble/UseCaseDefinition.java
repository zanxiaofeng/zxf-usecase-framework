package com.example.myapp.framework.assemble;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import org.springframework.http.HttpMethod;

/**
 * 单个用例的配置定义（对应 YAML 中 usecase.definitions 的一个元素）。
 *
 * <p>本类取「校验后真相」世界观（java-coding-standard §4.2）：必填字段声明非空，
 * 绑定器 setter 填充后经 {@code UseCaseProperties @Validated} 在绑定期拒绝非法值，
 * 消费方只见已校验实例、不做 null check；编程式装配入口（全参构造器，不经 Bean
 * Validation）的同等校验由 UseCaseAssembler 承担（含注解表达不了的交叉规则）。</p>
 */
@Data
@NoArgsConstructor   // 配置绑定器走 JavaBean setter 绑定
@AllArgsConstructor  // 编程式装配的便捷构造（@Nullable 经 lombok.config 复制到参数）
@SuppressWarnings("NullAway.Init")   // 字段由绑定器反射填充，源码中无显式赋值路径
public class UseCaseDefinition {

    /** 用例唯一标识（YAML 绑定期拒绝空白） */
    @NotBlank
    private String id;

    /** 描述（仅用于启动日志与治理） */
    @Nullable
    private String description;

    /** 是否共享用例：true 时不绑定 endpoint、不参与路由，只能被 type=usecase 的 step 嵌入引用；缺省 false */
    private boolean shared;

    /** 对外端点（shared 用例可缺省） */
    @Valid
    @Nullable
    private Endpoint endpoint;

    /** 有序步骤列表（YAML 绑定期拒绝缺失/空/null 元素） */
    @NotEmpty
    private List<@NotNull @Valid StepDefinition> steps;

    /** 对外端点定义。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @SuppressWarnings("NullAway.Init")
    public static class Endpoint {

        /** HTTP 方法（强类型，绑定期经 {@code HttpMethod.valueOf} 转换；SF7 起未知方法名构造自定义实例而非报错，YAML 用标准大写 GET/POST/…） */
        @Nullable
        private HttpMethod method;

        /** URI 模板，支持 {var}（YAML 绑定期拒绝空白） */
        @NotBlank
        private String path;

        /** 成功状态码，缺省 200（YAML 绑定期拒绝非法范围） */
        @Min(100)
        @Max(599)
        private int status = 200;
    }
}
