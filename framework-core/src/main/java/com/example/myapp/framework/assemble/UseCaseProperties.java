package com.example.myapp.framework.assemble;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 框架配置根，前缀 {@code usecase}。缺省时 definitions/errorMappings 为空集合，report 开启，trace 关闭。
 *
 * @param definitions   用例定义列表
 * @param errorMappings 异常类 → HTTP 状态码映射；key 支持全限定类名或简单类名
 * @param report        启动期数据流报告（{@code usecase.report}，默认开；仅日志，输出各用例静态可见的读写键视图）
 * @param trace         dev 模式 step trace（默认关）
 */
@ConfigurationProperties(prefix = "usecase")
public record UseCaseProperties(
        @DefaultValue List<UseCaseDefinition> definitions,
        @DefaultValue Map<String, Integer> errorMappings,
        @DefaultValue("true") boolean report,
        @DefaultValue Trace trace) {

    /**
     * dev 模式 step trace：enabled 开启后每步输出 INFO 轨迹（payload 类型迁移、新增 vars 键、耗时）；
     * includeValues 需显式二次开启（值快照截断输出，防大对象/敏感值撑爆日志）。
     */
    public record Trace(@DefaultValue("false") boolean enabled, @DefaultValue("false") boolean includeValues) {
    }
}
