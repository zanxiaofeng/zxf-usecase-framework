package com.example.myapp.framework.assemble;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * 框架配置根，前缀 {@code usecase}。缺省时两项均为空集合/空 Map。
 *
 * @param definitions   用例定义列表
 * @param errorMappings 异常类 → HTTP 状态码映射；key 支持全限定类名或简单类名
 */
@ConfigurationProperties(prefix = "usecase")
public record UseCaseProperties(
        @DefaultValue List<UseCaseDefinition> definitions,
        @DefaultValue Map<String, Integer> errorMappings) {
}
