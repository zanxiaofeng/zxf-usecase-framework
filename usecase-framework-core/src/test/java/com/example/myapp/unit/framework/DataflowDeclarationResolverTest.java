package com.example.myapp.unit.framework;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.dataflow.Dataflow;
import com.example.myapp.framework.core.dataflow.DataflowKey;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.assemble.DataflowDeclarationResolver;
import com.example.myapp.framework.assemble.StepDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内置 step 的数据流声明推导：从 raw config 推导读写键；ref/扩展类型透传 {@code Step#dataflow()}。
 */
class DataflowDeclarationResolverTest {

    private static final Step ANONYMOUS_STEP = context -> context.setPayload("x");

    private Dataflow derive(StepDefinition definition) {
        return DataflowDeclarationResolver.derive(definition, ANONYMOUS_STEP);
    }

    @Test
    void starterDeclaresBizWritesAndValueExpressionReads() {
        Dataflow dataflow = derive(new StepDefinition("start", "starter", null,
                Map.of("keys", Map.of("businessId", "#biz.tenant", "channel", "'web'"))));

        assertThat(dataflow.unknown()).isFalse();
        assertThat(dataflow.writes())
                .containsExactlyInAnyOrder(DataflowKey.biz("businessId"), DataflowKey.biz("channel"));
        assertThat(dataflow.reads()).containsExactly(DataflowKey.biz("tenant"));
    }

    @Test
    void starterWithoutKeysDeclaresNothing() {
        Dataflow dataflow = derive(new StepDefinition("start", "starter", null, Map.of()));

        assertThat(dataflow.writes()).isEmpty();
        assertThat(dataflow.reads()).isEmpty();
    }

    @Test
    void spelStepsDeclareAsOrPayloadWriteAndExpressionReads() {
        Dataflow withAs = derive(new StepDefinition("fetch", "dataLoader", null,
                Map.of("expression", "#biz.businessId", "as", "credit")));
        Dataflow toPayload = derive(new StepDefinition("load", "dataTransformer", null,
                Map.of("expression", "#vars.credit")));

        assertThat(withAs.writes()).containsExactly(DataflowKey.vars("credit"));
        assertThat(withAs.reads()).containsExactly(DataflowKey.biz("businessId"));
        assertThat(toPayload.writes()).containsExactly(DataflowKey.payload());
        assertThat(toPayload.reads()).containsExactly(DataflowKey.vars("credit"));
    }

    @Test
    void codecWithoutSourceDefaultsToPayloadRead() {
        Dataflow dataflow = derive(new StepDefinition("encode", "encoder", null, Map.of("algorithm", "base64")));

        assertThat(dataflow.reads()).containsExactly(DataflowKey.payload());
        assertThat(dataflow.writes()).containsExactly(DataflowKey.payload());
    }

    @Test
    void subUseCaseDefaultsInputToPayloadRead() {
        Dataflow chained = derive(new StepDefinition(null, "usecase", "child", Map.of()));
        Dataflow sideOutput = derive(new StepDefinition(null, "usecase", "child", Map.of("input", "#payload", "as", "userDto")));

        assertThat(chained.reads()).containsExactly(DataflowKey.payload());
        assertThat(chained.writes()).containsExactly(DataflowKey.payload());
        // 旁路 as 之外补 payload 写声明：子链初始 payload 落写/恢复按录制窗口归属本 step（录制模型近似）
        assertThat(sideOutput.writes()).containsExactlyInAnyOrder(DataflowKey.vars("userDto"), DataflowKey.payload());
    }

    @Test
    void readOnlyStepsDeclareNoWrites() {
        Dataflow validator = derive(new StepDefinition("check", "validator", null,
                Map.of("expression", "#biz.businessId != null")));
        Dataflow logging = derive(new StepDefinition("log", "logging", null, Map.of("message", "#vars.credit")));
        Dataflow publisher = derive(new StepDefinition("publish", "eventPublisher", null,
                Map.of("event", "#payload")));

        for (Dataflow dataflow : List.of(validator, logging, publisher)) {
            assertThat(dataflow.writes()).isEmpty();
            assertThat(dataflow.unknown()).isFalse();
        }
        assertThat(validator.reads()).containsExactly(DataflowKey.biz("businessId"));
        assertThat(logging.reads()).containsExactly(DataflowKey.vars("credit"));
    }

    @Test
    void nonBuiltInTypeFallsBackToStepDeclaration() {
        Step declared = new Step() {
            @Override
            public void execute(StepContext context) {
                context.setPayload("x");
            }

            @Override
            public Dataflow dataflow() {
                return Dataflow.declaring().reads("vars.credit").writes("payload").build();
            }
        };

        Dataflow dataflow = DataflowDeclarationResolver.derive(
                new StepDefinition("merge", null, "customStep", Map.of()), declared);

        assertThat(dataflow.reads()).containsExactly(DataflowKey.vars("credit"));
        assertThat(dataflow.writes()).containsExactly(DataflowKey.payload());
    }

    @Test
    void illegalAsKeyFailsAssembly() {
        // as 键 "a b" 含空白（@Pattern \S+ 拦不住编程式构造的 config）→ 装配期 fail-fast
        StepDefinition definition = new StepDefinition("fetch", "dataLoader", null,
                Map.of("expression", "'x'", "as", "a b"));

        assertThatThrownBy(() -> derive(definition))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("a b");
    }

    @Test
    void bodyReadsAreSkippedInDeclaration() {
        // request body 不是 context 三通道，读取声明跳过（运行期录制照常记录）
        Dataflow dataflow = derive(new StepDefinition("validate", "validator", null,
                Map.of("expression", "#body.userId != null", "and", "#path.id != null")));

        assertThat(dataflow.reads()).isEmpty();
    }
}
