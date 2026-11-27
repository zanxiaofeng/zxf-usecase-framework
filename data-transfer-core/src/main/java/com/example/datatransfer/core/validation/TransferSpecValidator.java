package com.example.datatransfer.core.validation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import com.example.datatransfer.core.spec.TransferSpec;

import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.MapperFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLParser;

/**
 * TransferSpec 校验器（设计文档 §9.3）：networknt json-schema-validator 3.x
 * （SchemaRegistry / Schema / List&lt;Error&gt;）。
 *
 * <p>加载链路只解析一次：YAML 文本 → 解析树 → JSON Schema 校验与 TransferSpec 绑定共用同一棵树
 * （外部评审二轮 P1-3：历史行为是 Schema 校验与绑定各自解析一遍，重复键被静默后值覆盖，
 * 前一份配置丢失而校验照样通过）。重复键与 YAML 别名（{@code *anchor}）在解析期即被拒绝（顶层与任意嵌套层级）。</p>
 *
 * <p>Schema 校验是第一道（{@code additionalProperties: false} 拒绝未知键）；
 * YAML mapper 同时显式开启 {@code FAIL_ON_UNKNOWN_PROPERTIES} 作为不走 Schema
 * 路径时的兜底（Jackson 3 默认忽略未知属性）。</p>
 */
public class TransferSpecValidator {

    private static final String SCHEMA_RESOURCE = "/schemas/transfer-spec.json";

    /** 全部校验器共享一个方言注册表（线程安全，meta-schema 已预加载；与 usecase-framework ValidatorStepFactory 同一形态） */
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /** networknt 3.x：Schema 实例线程安全，应缓存复用 */
    private final Schema schema;

    private final YAMLMapper yamlMapper = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)   // 重复键：后值不得静默覆盖前值
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)   // nullPolicy: skip ≡ SKIP
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public TransferSpecValidator() {
        String schemaData = readResource(SCHEMA_RESOURCE);
        // Schema 数据自带 $schema 声明时以其声明的方言为准；未声明时用默认方言
        this.schema = SCHEMA_REGISTRY.getSchema(schemaData, InputFormat.JSON);
    }

    /** 校验 YAML 配置文件 */
    public ValidationResult validate(Path yamlFile) throws IOException {
        return validate(Files.readString(yamlFile, StandardCharsets.UTF_8));
    }

    /** 校验 YAML 字符串内容；解析失败（含重复键）同样以校验失败返回 */
    public ValidationResult validate(String yamlContent) {
        Parsed parsed = parse(yamlContent);
        if (parsed.failure() != null) {
            return ValidationResult.parseFailure(parsed.failure());
        }
        return new ValidationResult(schema.validate(parsed.document()));
    }

    /** 校验并直接加载为 TransferSpec，校验失败则抛 {@link SpecValidationException} */
    public TransferSpec validateAndLoad(InputStream in, String source) throws IOException {
        Parsed parsed = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        if (parsed.failure() != null) {
            throw new SpecValidationException(
                    "TransferSpec parsing failed (" + source + "): " + parsed.failure(), Collections.emptyList());
        }
        ValidationResult result = new ValidationResult(schema.validate(parsed.document()));
        if (!result.isValid()) {
            throw new SpecValidationException(
                    "TransferSpec validation failed (" + source + "):\n"
                            + String.join("\n", result.getErrorMessages()),
                    result.errors());
        }
        return yamlMapper.treeToValue(parsed.document(), TransferSpec.class);
    }

    /** 一次解析的产物：成功得到解析树，失败得到原因（重复键、YAML 别名、语法错误等） */
    private record Parsed(@Nullable JsonNode document, @Nullable String failure) {
    }

    private Parsed parse(String yamlContent) {
        JsonNode document;
        try {
            document = yamlMapper.readTree(yamlContent);
        } catch (StreamReadException malformed) {
            return new Parsed(null, malformed.getOriginalMessage());
        }
        String alias = findAlias(yamlContent);
        return alias == null ? new Parsed(document, null) : new Parsed(null, alias);
    }

    /**
     * YAML 别名（{@code *anchor}）在加载链里被拒绝：Jackson 的 YAML 解析器不解析别名——别名会被当成值为锚点名字的字符串
     * （{@code to: *common} 的目标键成了 {@code "common"}），Schema 校验照样通过而数据写到错误的位置。
     * 锚点本身（{@code &name}）无害；合并键 {@code <<} 由 Schema 以未定义属性拒绝。
     *
     * @return 首个别名的失败原因；文档不含别名时返回 null
     */
    private @Nullable String findAlias(String yamlContent) {
        try (JsonParser parser = yamlMapper.createParser(yamlContent)) {
            while (parser.nextToken() != null) {
                if (parser instanceof YAMLParser yamlParser && yamlParser.isCurrentAlias()) {
                    String name = parser.currentName();
                    return "YAML alias '*" + parser.getText() + "'"
                            + (name == null ? "" : " (value of '" + name + "')")
                            + " is not supported: aliases are not resolved and would be read as the literal string '"
                            + parser.getText() + "'; write the value out instead";
                }
            }
        }
        return null;
    }

    private static String readResource(String resource) {
        try (InputStream in = TransferSpecValidator.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("schema resource not found: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load TransferSpec schema", e);
        }
    }

    public static class ValidationResult {

        private final @Nullable List<Error> errors;
        /** 解析期失败原因（重复键等）；非 null 表示文档未能解析为树 */
        private final @Nullable String parseFailure;

        ValidationResult(@Nullable List<Error> errors) {
            this.errors = errors;
            this.parseFailure = null;
        }

        private ValidationResult(String parseFailure) {
            this.errors = null;
            this.parseFailure = parseFailure;
        }

        static ValidationResult parseFailure(String message) {
            return new ValidationResult(message);
        }

        public boolean isValid() {
            return parseFailure == null && (errors == null || errors.isEmpty());
        }

        public List<Error> errors() {
            return errors == null ? List.of() : errors;
        }

        public List<String> getErrorMessages() {
            if (parseFailure != null) {
                return List.of(parseFailure);
            }
            return errors().stream()
                    .map(e -> e.getInstanceLocation() + ": " + e.getMessage())
                    .toList();
        }
    }

    public static class SpecValidationException extends RuntimeException {

        private final List<Error> validationErrors;

        public SpecValidationException(String message, List<Error> errors) {
            super(message);
            this.validationErrors = List.copyOf(errors);
        }

        public List<Error> getValidationErrors() {
            return validationErrors;
        }
    }
}
