package com.example.myapp.framework.assemble;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * 框架配置根，前缀 {@code usecase}。
 *
 * @param definitions   用例定义列表
 * @param errorMappings 异常类 → HTTP 状态码映射；key 支持全限定类名或简单类名
 */
@ConfigurationProperties(prefix = "usecase")
public record UseCaseProperties(List<UseCaseDefinition> definitions, Map<String, Integer> errorMappings) {

    public UseCaseProperties {
        definitions = definitions == null ? List.of() : List.copyOf(definitions);
        errorMappings = errorMappings == null ? Map.of() : Map.copyOf(errorMappings);
    }
}
