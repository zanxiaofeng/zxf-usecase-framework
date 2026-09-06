package com.example.myapp.unit.framework;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.core.UseCaseTrace;
import com.example.myapp.framework.core.dataflow.AccessEvent;
import com.example.myapp.framework.core.dataflow.DataflowBook;
import com.example.myapp.framework.core.dataflow.DataflowKey;
import com.example.myapp.framework.core.dataflow.DataflowOptions;
import com.example.myapp.framework.core.dataflow.DataflowRecorder;
import com.example.myapp.framework.core.dataflow.DataflowTrace;
import com.example.myapp.framework.core.dataflow.StepRef;
import com.example.myapp.framework.core.invoke.UseCaseInvoker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * 用例执行期的数据链录制：开关、窗口归属、external 复用与 owned 生命周期、invoker 续链。
 */
class UseCaseRecordingTest {

    /** 具名 step（lambda 的 getSimpleName() 为空串，事件归属需要确定名） */
    private record NamedStep(String stepName, Consumer<StepContext> action) implements Step {
        @Override
        public void execute(StepContext context) {
            action.accept(context);
        }

        @Override
        public String name() {
            return stepName;
        }
    }

    private UseCase useCase(String id, boolean recordEnabled, Step... steps) {
        DataflowOptions options = recordEnabled
                ? DataflowOptions.recording(new DataflowBook())
                : DataflowOptions.disabled();
        return new UseCase(id, null, null, List.of(steps), false, UseCaseTrace.DISABLED, options);
    }

    private StepContext recordedContext() {
        StepContext context = StepContext.standalone();
        context.attach(DataflowRecorder.external());
        return context;
    }

    @Test
    void recordsStepAccessEventsWhenEnabled() {
        UseCase useCase = useCase("uc1", false,   // recorder 复用优先：context 已挂 external 即录制
                new NamedStep("loader", context -> context.setPayload("raw")),
                new NamedStep("saver", context -> context.putVar("saved", true)));
        StepContext context = recordedContext();

        useCase.execute(context);

        DataflowTrace trace = context.recorder().snapshot();
        assertThat(trace.events()).extracting(AccessEvent::useCaseId, AccessEvent::stepName, AccessEvent::key)
                .containsExactly(
                        tuple("uc1", "loader", DataflowKey.payload()),
                        tuple("uc1", "saver", DataflowKey.vars("saved")));
    }

    @Test
    void disabledByDefaultMeansNoRecorder() {
        UseCase useCase = useCase("uc1", false, new NamedStep("a", context -> context.setPayload("x")));
        StepContext context = StepContext.standalone();

        useCase.execute(context);

        assertThat(context.recorder()).isNull();
    }

    @Test
    void enabledUseCaseCreatesOwnedRecorderAndDetachesAfter() {
        UseCase useCase = useCase("uc1", true, new NamedStep("a", context -> context.setPayload("x")));
        StepContext context = StepContext.standalone();

        useCase.execute(context);

        // owned recorder 随 execute 生命周期：结束即 detach，防同 context 复用串数据
        assertThat(context.recorder()).isNull();
    }

    @Test
    void childUseCaseEventsAttributeToChildId() {
        UseCase child = useCase("child", false, new NamedStep("child-step", context -> context.setPayload("child-payload")));
        UseCase parent = useCase("parent", false, new NamedStep("parent-step", context -> {
            child.execute(context);   // 共享上下文的嵌套执行
            context.setPayload("parent-final");
        }));
        StepContext context = recordedContext();

        parent.execute(context);

        // 按 WRITE 事件断言归属（子用例收尾的 payload 读取等 READ 边界效应落在父窗口，不参与归属判定）
        List<AccessEvent> writes = context.recorder().snapshot().events().stream()
                .filter(event -> event.op() == AccessEvent.Op.WRITE)
                .toList();
        assertThat(writes)
                .extracting(AccessEvent::useCaseId, AccessEvent::stepName)
                .containsExactly(
                        tuple("child", "child-step"),
                        tuple("parent", "parent-step"));
    }

    @Test
    void invokerIsolatedAndStandaloneContinueChain() {
        UseCase child = useCase("child", false, new NamedStep("child-step", context -> context.putVar("fromChild", 1)));
        UseCaseRegistry registry = new UseCaseRegistry(List.of(child));
        UseCaseInvoker invoker = new UseCaseInvoker(() -> registry);
        UseCase parent = useCase("parent", false, new NamedStep("parent-step", context -> {
            invoker.invokeIsolated("child", "in", context);
            invoker.invokeStandalone("child", "in");
        }));
        StepContext context = recordedContext();

        parent.execute(context);

        // isolate 与 standalone 子链均并入同一 trace（usecaseId 维度区分归属）
        assertThat(context.recorder().snapshot().events())
                .extracting(AccessEvent::useCaseId, AccessEvent::stepName)
                .contains(tuple("child", "child-step"), tuple("child", "child-step"));
    }

    @Test
    void traceQueriesExposeWritersAndReaders() {
        UseCase useCase = useCase("uc1", false,
                new NamedStep("writer", context -> context.putVar("credit", 650)),
                new NamedStep("reader", context -> context.getVar("credit")));
        StepContext context = recordedContext();

        useCase.execute(context);

        DataflowTrace trace = context.recorder().snapshot();
        assertThat(trace.writersOf("vars.credit")).containsExactly(StepRef.of("uc1", "writer"));
        assertThat(trace.readersOf("vars.credit")).containsExactly(StepRef.of("uc1", "reader"));
        assertThat(trace.writersOf("vars.nobody")).isEmpty();
        assertThat(trace.useCases()).containsExactly("uc1");
    }
}
