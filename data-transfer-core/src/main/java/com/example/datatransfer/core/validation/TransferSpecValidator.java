package com.example.datatransfer.core.validation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import com.example.datatransfer.core.spec.TransferSpec;

import lombok.Data;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * TransferSpec 校验器（设计文档 §9.3）：networknt json-schema-validator 3.x
 * （SchemaRegistry / Schema / List&lt;Error&gt;，原生 JSON/YAML 输入）。
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

    /** 校验 YAML 字符串内容（3.x 原生支持 YAML 输入；Jackson 3 异常为非受检） */
    public ValidationResult validate(String yamlContent) {
        return new ValidationResult(schema.validate(yamlContent, InputFormat.YAML));
    }

    /** 校验并直接加载为 TransferSpec，校验失败则抛 {@link SpecValidationException} */
    public TransferSpec validateAndLoad(InputStream in, String source) throws IOException {
        String yamlContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        ValidationResult result = validate(yamlContent);
        if (!result.isValid()) {
            throw new SpecValidationException(
                    "TransferSpec validation failed (" + source + "):\n"
                            + String.join("\n", result.getErrorMessages()),
                    result.getErrors());
        }
        return yamlMapper.readValue(yamlContent, TransferSpec.class);
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

    @Data
    public static class ValidationResult {

        private final @Nullable List<Error> errors;

        public boolean isValid() {
            return errors == null || errors.isEmpty();
        }

        public List<String> getErrorMessages() {
            if (errors == null) {
                return Collections.emptyList();
            }
            return errors.stream()
                    .map(e -> e.getInstanceLocation() + ": " + e.getMessage())
                    .collect(Collectors.toList());
        }
    }

    public static class SpecValidationException extends RuntimeException {

        private final List<Error> validationErrors;

        public SpecValidationException(String message, List<Error> errors) {
            super(message);
            this.validationErrors = errors;
        }

        public List<Error> getValidationErrors() {
            return validationErrors;
        }
    }
}
