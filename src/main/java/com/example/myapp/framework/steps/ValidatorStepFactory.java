package com.example.myapp.framework.steps;

import com.example.myapp.framework.assemble.StepConfig;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.config.StepDefinition;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepValidationException;
import com.example.myapp.framework.core.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * validator 步骤工厂：expression 与 schema 二选一（互斥校验，fail-fast）；
 * schema 在装配期预编译为 {@link Schema}，避免运行期重复解析。
 *
 * <p>底层使用 networknt json-schema-validator 3.x（基于 Jackson 3，与 Spring Boot 4 兼容），
 * 缺省方言为 JSON Schema 2020-12。</p>
 */
public final class ValidatorStepFactory implements StepFactory {

    public static final String TYPE = "validator";

    /** 全部 validator 共享一个方言注册表（线程安全，meta-schema 已预加载） */
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private final StepExpressionEvaluator evaluator;
    private final ObjectMapper objectMapper;

    public ValidatorStepFactory(StepExpressionEvaluator evaluator, ObjectMapper objectMapper) {
        this.evaluator = evaluator;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Step create(StepDefinition definition) {
        StepConfig config = StepConfig.of(definition);
        String name = definition.nameOr(TYPE);
        String target = config.stringOr("target", "#payload");
        String expression = config.optionalString("expression");
        Map<String, Object> schemaMap = config.mapOrEmpty("schema");

        boolean hasExpression = expression != null;
        boolean hasSchema = !schemaMap.isEmpty();
        if (hasExpression == hasSchema) {
            throw new UseCaseAssemblyException(
                    "step [%s]: exactly one of 'expression' or 'schema' must be configured".formatted(name));
        }
        String message = config.optionalString("message");
        String errorCode = config.stringOr("errorCode", StepValidationException.DEFAULT_CODE);

        Schema schema = null;
        if (hasSchema) {
            try {
                JsonNode schemaNode = objectMapper.valueToTree(normalizeConfigValue(schemaMap));
                schema = SCHEMA_REGISTRY.getSchema(schemaNode);
            } catch (Exception e) {
                throw new UseCaseAssemblyException("step [%s]: invalid JSON schema: %s".formatted(name, e.getMessage()), e);
            }
        }
        return new ValidatorStep(name, target, expression, schema, message, errorCode, evaluator, objectMapper);
    }

    /**
     * 递归还原 Spring Boot 对 {@code Map<String, Object>} 的绑定失真：
     * 目标类型为 Object 时，YAML 列表（{@code required: [userId, name]}）会被绑定成
     * 索引 Map（{@code {0=userId, 1=name}}），这里把「键全为非负整数」的 Map 还原为 List。
     */
    private static Object normalizeConfigValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (!map.isEmpty()
                    && map.keySet().stream().allMatch(key -> key instanceof String s && s.matches("\\d+"))) {
                return map.entrySet().stream()
                        .sorted(java.util.Comparator.comparingInt(e -> Integer.parseInt((String) e.getKey())))
                        .map(e -> normalizeConfigValue(e.getValue()))
                        .toList();
            }
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), normalizeConfigValue(v)));
            return result;
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(ValidatorStepFactory::normalizeConfigValue).toList();
        }
        return value;
    }
}
