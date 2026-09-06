package com.example.myapp.framework.steps.config;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;

/**
 * httpRequester 步骤的 config schema。认证 scheme 的存在性与 options 校验依赖注册表，
 * 仍由 HttpRequesterStepFactory 在装配期完成（注解无法表达）。
 *
 * <p>@Data + 字段初始值模式：默认值直接写在字段上，Jackson 绑定时仅覆盖 YAML 中出现的属性。</p>
 */
// NullAway.Init：字段由 Jackson 绑定填充（不走构造器初始化），非空约束由 Bean Validation 在绑定后承担
@Data
@SuppressWarnings("NullAway.Init")
public class HttpRequesterConfig {

    /** HTTP 方法，缺省 GET；须为标准方法名（大写）——装配期白名单拦截拼写错误与小写变体
     *  （SF7 的 HttpMethod 对未知名字构造自定义实例而非报错；ACCEPT_CASE_INSENSITIVE_ENUMS 不适用于非枚举类型） */
    private HttpMethod method = HttpMethod.GET;

    /** 目标 URL（必填），支持 {var} 模板 */
    @NotBlank
    private String url;

    /** URI 模板变量，值支持 SpEL；键不得空白（空白键会让 URL 模板替换静默失效） */
    private Map<@NotBlank String, Object> uriVariables = Map.of();

    /** 请求头，值支持字面量 / 模板 / SpEL；键不得空白 */
    private Map<@NotBlank String, Object> headers = Map.of();

    /** 请求体 SpEL 表达式，结果序列化为 JSON */
    private @Nullable String body;

    /** 认证配置；缺省不携带认证头 */
    @Valid
    private @Nullable AuthConfig auth;

    /** 结果写入 #vars 的旁路键；缺省写回 payload；@Pattern（非 @NotBlank）——null 表示未配置须放行，
     *  仅拒绝显式配置了空白值的手滑（@NotBlank 会连 null 一起拒，破坏「缺省」语义） */
    @Pattern(regexp = "\\S+", message = "must not be blank when set")
    private @Nullable String as;

    /** 认证块：scheme 必填（声明了 auth 就必须给出 scheme）；options 为 scheme 相关的开放 Map */
    @Data
    public static class AuthConfig {

        @NotBlank
        private String scheme;

        private Map<String, Object> options = Map.of();
    }
}
