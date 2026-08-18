package com.example.myapp.unit.framework;

import com.example.myapp.framework.core.AbstractUseCaseClient;
import com.example.myapp.framework.core.SimpleExchangeRequest;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.StepContextHolder;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseInvoker;
import com.example.myapp.framework.core.UseCaseRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UseCaseInvoker（Java 代码调用子用例）三种语义：
 * 管道内共享调用（父 payload 恢复、vars/biz 互通）、隔离调用、独立调用；
 * 以及 StepContextHolder 在管道执行期的绑定/恢复。
 */
class UseCaseInvokerTest {

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
        StepContext parent = new StepContext(SimpleExchangeRequest.of("GET", "/x"));
        parent.setPayload("P1");
        parent.putBiz("businessId", "u1");
        Object[] resultHolder = new Object[1];
        Step parentStep = context -> {
            // 管道内 Java 代码无参调用：自动继承当前上下文
            resultHolder[0] = invoker.invoke("childUc", context.getBiz("businessId"));
            assertThat(StepContextHolder.current()).isSameAs(parent);
        };
        new UseCase("parentUc", null, null, List.of(parentStep)).execute(parent);

        assertThat(resultHolder[0]).isEqualTo("u1-child");        // 子结果经返回值返回
        assertThat(parent.getPayload()).isEqualTo("P1");          // Java 调用是函数式取值：父 payload 恢复
        assertThat(parent.getVar("childVar")).isEqualTo("v");     // 共享模式：子的 vars 写入可见
        assertThat(parent.getBiz("childKey")).isEqualTo("k");     // 共享模式：子的 biz 写入可见
    }

    @Test
    void invokeIsolated_doesNotLeakVarsOrBiz() {
        UseCaseInvoker invoker = invokerWithChild();
        StepContext parent = new StepContext(SimpleExchangeRequest.of("GET", "/x"));
        parent.setPayload("P1");
        parent.putBiz("businessId", "u1");

        Object[] resultHolder = new Object[1];
        Step parentStep = context -> resultHolder[0] = invoker.invokeIsolated("childUc", context.getBiz("businessId"));
        new UseCase("parentUc", null, null, List.of(parentStep)).execute(parent);

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

        StepContext parent = new StepContext(SimpleExchangeRequest.of("GET", "/x"));
        Step parentStep = context -> {
            invoker.invokeIsolated("childUc", "in", context);
            assertThat(StepContextHolder.current()).isSameAs(parent);   // 嵌套结束后恢复父
        };
        new UseCase("parentUc", null, null, List.of(parentStep)).execute(parent);

        assertThat(StepContextHolder.current()).isNull();               // 父管道结束后清空
    }
}
