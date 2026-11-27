package com.example.datatransfer.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.datatransfer.core.flatten.FlatMapProcessor;
import com.example.datatransfer.core.validation.TransferSpecValidator;

import tools.jackson.databind.JsonNode;

/**
 * 设计文档示例进入配置加载链（外部评审二轮 P1-2）：正文 §9.1 内嵌的 Schema 必须与仓库文件语义一致，
 * 文档里的 YAML 示例必须通过同一套 JSON Schema 校验，完整示例必须能装配成引擎——
 * 文档与仓库再漂移时在这里失败，而不是靠附录解释「正文为什么不能运行」。
 */
class DesignDocExamplesTest {

    /** surefire 的工作目录是模块目录；文档不在（例如独立发布的 SDK）时跳过 */
    private static final Path DESIGN_DOC =
            Path.of("..", "docs", "基于JSON Flatten的声明式Data Transfer框架.md");

    private static final Set<String> SPEC_TOP_LEVEL_KEYS = Set.of("version", "name", "options", "sources", "rules",
            "intermediate", "computed", "defaults", "validations", "rewrites", "observability");

    private static final TransferSpecValidator VALIDATOR = new TransferSpecValidator();

    @Test
    void embeddedSchema_isIdenticalToBundledSchema() throws IOException {
        String embedded = codeBlocks(designDoc(), "json").stream()
                .filter(block -> block.contains("\"$defs\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("设计文档 §9.1 未找到内嵌 Schema"));

        JsonNode bundled = new FlatMapProcessor().mapper()
                .readTree(getClass().getResourceAsStream("/schemas/transfer-spec.json"));

        assertThat(new FlatMapProcessor().mapper().readTree(embedded))
                .as("正文 §9.1 的 Schema 与仓库 schemas/transfer-spec.json 必须一致")
                .isEqualTo(bundled);
    }

    @Test
    void yamlExamples_passTheBundledSchemaValidation() throws IOException {
        List<Example> examples = specExamples();

        assertThat(examples).isNotEmpty();
        for (Example example : examples) {
            var result = VALIDATOR.validate(example.yaml());
            assertThat(result.isValid())
                    .as(() -> "设计文档示例未通过 Schema 校验（块首行: " + example.firstLine() + "）: "
                            + result.getErrorMessages())
                    .isTrue();
        }
    }

    @Test
    void completeSpecExamples_assembleIntoEngine() throws IOException {
        List<Example> complete = specExamples().stream().filter(Example::complete).toList();

        // 完整示例：§2.4（order-transfer.yaml）与 §7.2（电商订单 → CRM）
        assertThat(complete).hasSizeGreaterThanOrEqualTo(2);
        for (Example example : complete) {
            var spec = VALIDATOR.validateAndLoad(
                    new java.io.ByteArrayInputStream(example.yaml().getBytes(StandardCharsets.UTF_8)), example.firstLine());
            new TransferEngine(spec);   // 装配期校验照常通过
        }
    }

    private String designDoc() throws IOException {
        assumeTrue(Files.exists(DESIGN_DOC), "设计文档不在本模块可及范围内，跳过");
        return Files.readString(DESIGN_DOC, StandardCharsets.UTF_8);
    }

    /** 文档里所有「片段属于 TransferSpec 顶层键」的 YAML 块；缺 version / name / rules 的片段补齐后参与校验 */
    private List<Example> specExamples() throws IOException {
        List<Example> examples = new ArrayList<>();
        for (String block : codeBlocks(designDoc(), "yaml")) {
            // 以「# ❌」开头的块是文档里标注的反例（§9.2、D.6.3），不参与「必须通过」断言
            if (block.stripLeading().startsWith("# ❌")) {
                continue;
            }
            String firstLine = block.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .findFirst().orElse("");
            if (!firstLine.matches("[a-zA-Z]+:.*")
                    || !SPEC_TOP_LEVEL_KEYS.contains(firstLine.substring(0, firstLine.indexOf(':')))) {
                continue;
            }
            boolean complete = block.contains("\nversion:") || block.startsWith("version:");
            examples.add(new Example(complete, firstLine, complete ? block : completed(block)));
        }
        return examples;
    }

    private static String completed(String fragment) {
        StringBuilder yaml = new StringBuilder("version: \"1.0\"\nname: \"doc-example\"\n");
        if (!fragment.lines().anyMatch(line -> line.startsWith("rules:"))) {
            yaml.append("rules:\n  - from: \"a\"\n    to: \"b\"\n");
        }
        return yaml.append(fragment).toString();
    }

    /** 提取 markdown 中指定语言的围栏代码块 */
    private static List<String> codeBlocks(String markdown, String language) {
        List<String> blocks = new ArrayList<>();
        List<String> current = null;
        for (String line : markdown.split("\n")) {
            String stripped = line.stripLeading();
            if (current == null) {
                if (stripped.matches("```" + language + "\\s*")) {
                    current = new ArrayList<>();
                }
                continue;
            }
            if (stripped.equals("```")) {
                blocks.add(String.join("\n", current));
                current = null;
                continue;
            }
            current.add(line);
        }
        return blocks;
    }

    private record Example(boolean complete, String firstLine, String yaml) {
    }
}
