package com.example.usecase.framework.steps;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.example.usecase.framework.assemble.StepConfigs;
import com.example.usecase.framework.assemble.StepDefinition;
import com.example.usecase.framework.assemble.StepFactory;
import com.example.usecase.framework.core.spi.Step;
import com.example.usecase.framework.core.exception.UseCaseAssemblyException;
import com.example.usecase.framework.expression.StepExpressionEvaluator;
import com.example.usecase.framework.steps.config.ValidatorConfig;

/**
 * validator 步骤工厂：config schema 见 {@link ValidatorConfig}（expression 与 schema
 * 二选一的互斥约束由 {@code @AssertTrue} 声明式校验）；schema 在装配期预编译为
 * {@link Schema}，避免运行期重复解析。
 *
 * <p>底层使用 networknt json-schema-validator 3.x（基于 Jackson 3，与 Spring Boot 4 兼容），
 * 缺省方言为 JSON Schema 2020-12。</p>
 */
@RequiredArgsConstructor
public final class ValidatorStepFactory implements StepFactory {

    public static final String TYPE = "validator";

    /** 全部 validator 共享一个方言注册表（线程安全，meta-schema 已预加载） */
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private final StepExpressionEvaluator evaluator;
    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Step create(StepDefinition definition) {
        ValidatorConfig config = StepConfigs.bind(definition, ValidatorConfig.class);
        String name = definition.nameOr(TYPE);
        evaluator.precompileForStep(name, config.getExpression(), config.getTarget());   // 语法错误启动期 fail-fast

        Schema schema = null;
        if (config.getSchema() != null && !config.getSchema().isEmpty()) {
            schema = compileSchema(name, normalizeConfigValue(config.getSchema()));
        } else if (StringUtils.hasText(config.getSchemaRef())) {
            // 独立资源直读 JSON：不经属性绑定，无「全数字键 Map 被还原为 List」的失真，
            // 含数字键对象的 schema（如 {"const":{"0":"a"}}）走此通道
            schema = compileSchema(name, loadSchemaResource(name, config.getSchemaRef()));
        }
        return new ValidatorStep(name, config.getTarget(), config.getExpression(), schema, config.getMessage(),
                config.getErrorCode(), evaluator, objectMapper);
    }

    private Schema compileSchema(String stepName, Object normalizedSchema) {
        try {
            return SCHEMA_REGISTRY.getSchema(objectMapper.valueToTree(normalizedSchema));
        } catch (Exception e) {
            throw new UseCaseAssemblyException(
                    "step [%s]: invalid JSON schema: %s".formatted(stepName, e.getMessage()), e);
        }
    }

    /** 从 classpath 读取 JSON Schema 资源（schemaRef）：缺失/非对象结构/解析失败均装配期 fail-fast */
    private Map<String, Object> loadSchemaResource(String stepName, String schemaRef) {
        ClassPathResource resource = new ClassPathResource(schemaRef);
        if (!resource.exists()) {
            throw new UseCaseAssemblyException(
                    "step [%s]: schemaRef resource not found on classpath: %s".formatted(stepName, schemaRef));
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode node = objectMapper.readTree(in);
            if (!node.isObject()) {
                throw new UseCaseAssemblyException(
                        "step [%s]: schemaRef resource [%s] must contain a JSON object".formatted(stepName, schemaRef));
            }
            return objectMapper.treeToValue(node, new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            throw new UseCaseAssemblyException(
                    "step [%s]: failed to read schemaRef resource [%s]: %s".formatted(stepName, schemaRef, e.getMessage()), e);
        }
    }

    /**
     * 递归还原 Spring Boot 对 {@code Map<String, Object>} 的绑定失真：
     * 目标类型为 Object 时，YAML 列表（{@code required: [userId, name]}）会被绑定成
     * 索引 Map（{@code {0=userId, 1=name}}），这里把「键全为非负整数」的 Map 还原为 List。
     */
    private static Object normalizeConfigValue(Object value) {
        return switch (value) {
            case Map<?, ?> map -> normalizeMap(map);
            case List<?> list -> list.stream().map(ValidatorStepFactory::normalizeConfigValue).toList();
            default -> value;
        };
    }

    /** 键全为非负整数的 Map 还原为 List（按数值排序，修复 Boot 对开放 Map 的列表绑定失真），其余递归规范化 */
    private static Object normalizeMap(Map<?, ?> map) {
        if (!map.isEmpty()
                && map.keySet().stream().allMatch(key -> key instanceof String s && s.matches("\\d+"))) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparingInt(e -> Integer.parseInt((String) e.getKey())))
                    .map(e -> normalizeConfigValue(e.getValue()))
                    .toList();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), normalizeConfigValue(v)));
        return result;
    }
}
