package com.example.myapp.unit.framework;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.StepContextHolder;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.core.exception.UseCaseResultTypeException;
import com.example.myapp.framework.core.invoke.AbstractUseCaseClient;
import com.example.myapp.framework.core.invoke.UseCaseInvoker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UseCaseInvoker（Java 代码调用子用例）三种语义：
 * 管道内共享调用（父 payload 恢复、vars/biz 互通）、隔离调用、独立调用；
 * 以及 StepContextHolder 在管道执行期的绑定/恢复。
 */
class UseCaseInvokerTest {

    private static final String MDC_BIZ_KEY = "biz.businessId";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** 子用例：payload 追加 "-child"，并向 vars / biz 写入标记 */
    private UseCaseRegistry registryWithChild() {
        Step childStep = context -> {
            context.setPayload(context.getPayload(String.class) + "-child");
            context.putVar("childVar", "v");
            context.putBiz("childKey", "k");
        };
        UseCase child = new UseCase("childUc", "子用例", null, List.of(childStep), true);
        return new UseCaseRegistry(List.of(child));
    }

    /** 类型化客户端基类的最小实现（demo 用法同构） */
    static class StringChildClient extends AbstractUseCaseClient<String, String> {
        StringChildClient(UseCaseInvoker invoker) {
            super(invoker, "childUc", String.class);
        }
    }

    private UseCaseInvoker invokerWithChild() {
        UseCaseRegistry registry = registryWithChild();
        return new UseCaseInvoker(() -> registry);   // Supplier 延迟解析（切断 Bean 创建循环）
    }

    @Test
    void invokeInsidePipeline_sharesContext_andRestoresParentPayload() {
        UseCaseInvoker invoker = invokerWithChild();

        // 模拟父管道：执行期间 StepContextHolder 绑定父上下文
        StepContext parent = StepContext.standalone();
        parent.setPayload("P1");
        parent.putBiz("businessId", "u1");
        Object[] resultHolder = new Object[1];
        Step parentStep = context -> {
            // 管道内 Java 代码无参调用：自动继承当前上下文
            resultHolder[0] = invoker.invoke("childUc", context.getBiz("businessId"));
            assertThat(StepContextHolder.current()).isSameAs(parent);
        };
        new UseCase("parentUc", null, null, List.of(parentStep), false).execute(parent);

        assertThat(resultHolder[0]).isEqualTo("u1-child");        // 子结果经返回值返回
        assertThat(parent.getPayload()).isEqualTo("P1");          // Java 调用是函数式取值：父 payload 恢复
        assertThat(parent.getVar("childVar")).isEqualTo("v");     // 共享模式：子的 vars 写入可见
        assertThat(parent.getBiz("childKey")).isEqualTo("k");     // 共享模式：子的 biz 写入可见
    }

    @Test
    void invokeIsolated_doesNotLeakVarsOrBiz() {
        UseCaseInvoker invoker = invokerWithChild();
        StepContext parent = StepContext.standalone();
        parent.setPayload("P1");
        parent.putBiz("businessId", "u1");

        Object[] resultHolder = new Object[1];
        Step parentStep = context -> resultHolder[0] = invoker.invokeIsolated("childUc", context.getBiz("businessId"));
        new UseCase("parentUc", null, null, List.of(parentStep), false).execute(parent);

        assertThat(resultHolder[0]).isEqualTo("u1-child");        // biz 拷贝继承：子可读父的 businessId
        assertThat(parent.getPayload()).isEqualTo("P1");
        assertThat(parent.getVars()).doesNotContainKey("childVar");
        assertThat(parent.getBiz()).doesNotContainKey("childKey");
    }

    @Test
    void invokeStandalone_worksWithoutPipeline_andSeedsTraceId() {
        UseCaseInvoker invoker = invokerWithChild();
        assertThat(StepContextHolder.current()).isNull();         // 管道外

        Object result = invoker.invokeStandalone("childUc", "u9");

        assertThat(result).isEqualTo("u9-child");
        assertThat(StepContextHolder.current()).isNull();         // 执行后已恢复（不泄漏）
    }

    @Test
    void typedClient_delegatesToInvoker() {
        StringChildClient client = new StringChildClient(invokerWithChild());
        String result = client.invokeStandalone("u7");
        assertThat(result).isEqualTo("u7-child");
    }

    @Test
    void contextHolder_restoresAfterNestedExecution() {
        // 父管道 → 子用例（execute 内嵌套绑定）→ 返回后 holder 恢复为父；父结束 → holder 清空
        UseCase child = new UseCase("childUc", null, null,
                List.of(context -> context.setPayload("c")), true);
        UseCaseRegistry registry = new UseCaseRegistry(List.of(child));
        UseCaseInvoker invoker = new UseCaseInvoker(() -> registry);

        StepContext parent = StepContext.standalone();
        Step parentStep = context -> {
            invoker.invokeIsolated("childUc", "in", context);
            assertThat(StepContextHolder.current()).isSameAs(parent);   // 嵌套结束后恢复父
        };
        new UseCase("parentUc", null, null, List.of(parentStep), false).execute(parent);

        assertThat(StepContextHolder.current()).isNull();               // 父管道结束后清空
    }

    @Test
    void invokeIsolated_restoresParentMdcAfterReturn() {
        // 子用例内写 MDC（模拟 starter 输出到日志上下文）：biz 拷贝继承而 MDC 是线程级单例，
        // 返回时父的 MDC 现场必须恢复，否则父管道后续日志输出子用例的值
        Step childStep = context -> MDC.put(MDC_BIZ_KEY, "u2");
        UseCase child = new UseCase("childUc", null, null, List.of(childStep), true);
        UseCaseInvoker invoker = new UseCaseInvoker(() -> new UseCaseRegistry(List.of(child)));

        StepContext parent = StepContext.standalone();
        Step parentStep = context -> {
            MDC.put(MDC_BIZ_KEY, "u1");
            invoker.invokeIsolated("childUc", "in", context);
            assertThat(MDC.get(MDC_BIZ_KEY)).isEqualTo("u1");   // 子写入已随返回回滚
        };
        new UseCase("parentUc", null, null, List.of(parentStep), false).execute(parent);

        assertThat(MDC.get(MDC_BIZ_KEY)).isEqualTo("u1");
    }

    @Test
    void invokeStandalone_clearsMdcWrittenInside() {
        Step childStep = context -> MDC.put(MDC_BIZ_KEY, "u9");
        UseCase child = new UseCase("childUc", null, null, List.of(childStep), true);
        UseCaseInvoker invoker = new UseCaseInvoker(() -> new UseCaseRegistry(List.of(child)));

        invoker.invokeStandalone("childUc", "in");

        assertThat(MDC.get(MDC_BIZ_KEY)).isNull();              // 进入时为空 → 执行后彻底清空
    }

    @Test
    void invokeSharedOutsidePipelineFailsFast() {
        // 严格共享变体：管道外调用立即报错（而非静默退化为独立语义、traceId 断链）
        UseCaseInvoker invoker = invokerWithChild();
        assertThat(StepContextHolder.current()).isNull();

        assertThatThrownBy(() -> invoker.invokeShared("childUc", "u1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invokeShared")
                .hasMessageContaining("invokeStandalone");
    }

    @Test
    void invokeSharedInsidePipelineSharesContext() {
        UseCaseInvoker invoker = invokerWithChild();
        StepContext parent = StepContext.standalone();
        parent.setPayload("P1");
        parent.putBiz("businessId", "u1");

        Object[] resultHolder = new Object[1];
        Step parentStep = context -> resultHolder[0] = invoker.invokeShared("childUc", context.getBiz("businessId"));
        new UseCase("parentUc", null, null, List.of(parentStep), false).execute(parent);

        assertThat(resultHolder[0]).isEqualTo("u1-child");
        assertThat(parent.getBiz("childKey")).isEqualTo("k");   // 共享语义与 invoke 一致
    }

    @Test
    void typedClientReportsResultTypeMismatch() {
        // Fix 9.1：子用例末步输出与客户端声明类型不符 → 语义化异常（替代裸 ClassCastException）
        StringChildClient client = new StringChildClient(invokerWithChild()) {
        };
        // childUc 返回 String "u7-child"；声明 Integer 类型的客户端应报类型错
        AbstractUseCaseClient<String, Integer> wrongTyped =
                new AbstractUseCaseClient<String, Integer>(invokerWithChild(), "childUc", Integer.class) {
                };

        assertThat(client.invokeStandalone("u7")).isEqualTo("u7-child");
        assertThatThrownBy(() -> wrongTyped.invokeStandalone("u7"))
                .isInstanceOf(UseCaseResultTypeException.class)
                .hasMessageContaining("childUc")
                .hasMessageContaining("Integer")
                .hasMessageContaining("String");
    }
}
