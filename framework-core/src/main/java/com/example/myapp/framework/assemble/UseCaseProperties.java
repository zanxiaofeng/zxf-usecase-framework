package com.example.myapp.framework.assemble;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 框架配置根，前缀 {@code usecase}。缺省时 definitions/errorMappings 为空集合，report 开启，trace 关闭。
 *
 * <p>{@code @Validated} 激活绑定后的 Bean Validation：字段级约束（id 非空白、steps 非空、
 * endpoint.path、状态码范围）在绑定期 fail-fast，FailureAnalyzer 输出精确到 YAML 来源位置。
 * 交叉规则（id 唯一、ref 存在性、环检测、method 白名单、保留 biz 键）注解无法表达，
 * 仍由 UseCaseAssembler 在装配期校验（同时守卫编程式装配入口）。</p>
 *
 * @param definitions   用例定义列表（YAML 绑定期拒绝 null 元素）
 * @param errorMappings 异常类 → HTTP 状态码映射；key 支持全限定类名或简单类名，值须为合法 HTTP 状态码
 * @param report        启动期数据流报告（{@code usecase.report}，默认开；仅日志，输出各用例静态可见的读写键视图）
 * @param trace         dev 模式 step trace（默认关）
 */
@Validated
@ConfigurationProperties(prefix = "usecase")
public record UseCaseProperties(
        @DefaultValue List<@NotNull @Valid UseCaseDefinition> definitions,
        @DefaultValue Map<String, @Min(100) @Max(599) Integer> errorMappings,
        @DefaultValue("true") boolean report,
        @DefaultValue Trace trace) {

    /**
     * dev 模式 step trace：enabled 开启后每步输出 INFO 轨迹（payload 类型迁移、新增 vars 键、耗时）；
     * includeValues 需显式二次开启（值快照截断输出，防大对象/敏感值撑爆日志）。
     */
    public record Trace(@DefaultValue("false") boolean enabled, @DefaultValue("false") boolean includeValues) {
    }
}
