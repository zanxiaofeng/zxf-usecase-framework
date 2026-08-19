package com.example.myapp.unit.framework;

import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.invoke.UseCaseInvoker;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.SubUseCaseStepFactory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子用例调用步骤（type: usecase）的数据传递语义：
 * input 求值、串联/旁路（as）模式、isolate 隔离模式、共享模式下 vars/biz 共享。
 */
class SubUseCaseStepTest {

    private final StepExpressionEvaluator evaluator = new StepExpressionEvaluator(null);

    /** 子用例：payload 追加 "-child"，并向 vars / biz 写入标记（用于观察共享/隔离行为） */
    private UseCaseRegistry registryWithChild() {
        Step childStep = context -> {
            context.setPayload(context.getPayload(String.class) + "-child");
            context.putVar("childVar", "v");
            context.putBiz("childKey", "k");
        };
        UseCase child = new UseCase("childUc", "子用例", null, List.of(childStep), true);
        return new UseCaseRegistry(List.of(child));
    }

    private StepContext parentContext() {
        StepContext context = StepContext.standalone();
        context.setPayload("P1");
        context.putBiz("businessId", "u1");
        return context;
    }

    private Step createStep(UseCaseRegistry registry, Map<String, Object> config) {
        UseCaseInvoker invoker = new UseCaseInvoker(() -> registry);
        SubUseCaseStepFactory factory = new SubUseCaseStepFactory(() -> invoker, evaluator);
        return factory.create(new StepDefinition("sub", "usecase", "childUc", config));
    }

    @Test
    void passthroughMode_childResultBecomesParentPayload_andVarsBizShared() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("input", "#biz.businessId");
        Step step = createStep(registryWithChild(), config);

        StepContext parent = parentContext();
        step.execute(parent);

        // 串联模式：未配 as → 子结果成为父 payload
        assertThat(parent.getPayload()).isEqualTo("u1-child");
        // 共享模式：子的 vars / biz 写入对父可见
        assertThat(parent.getVar("childVar")).isEqualTo("v");
        assertThat(parent.getBiz("childKey")).isEqualTo("k");
    }

    @Test
    void asMode_resultToVarsAndParentPayloadRestored() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("input", "#biz.businessId");
        config.put("as", "childResult");
        Step step = createStep(registryWithChild(), config);

        StepContext parent = parentContext();
        step.execute(parent);

        assertThat(parent.getVar("childResult")).isEqualTo("u1-child");
        assertThat(parent.getPayload()).isEqualTo("P1");      // 父 payload 恢复
    }

    @Test
    void isolateMode_varsIsolatedAndBizCopyInherited() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("input", "#biz.businessId");
        config.put("as", "childResult");
        config.put("isolate", "true");
        Step step = createStep(registryWithChild(), config);

        StepContext parent = parentContext();
        step.execute(parent);

        assertThat(parent.getVar("childResult")).isEqualTo("u1-child");
        assertThat(parent.getPayload()).isEqualTo("P1");
        // 隔离：子写入的 vars 不回传
        assertThat(parent.getVars()).doesNotContainKey("childVar");
        // 隔离：biz 拷贝继承，子的修改不回传；但父原有的 businessId 对子可见（input 求值成功即证明）
        assertThat(parent.getBiz()).doesNotContainKey("childKey");
    }

    @Test
    void defaultInputIsPayload() {
        Step step = createStep(registryWithChild(), Map.of());   // 未配 input → 缺省 #payload

        StepContext parent = parentContext();
        step.execute(parent);

        assertThat(parent.getPayload()).isEqualTo("P1-child");
    }
}
