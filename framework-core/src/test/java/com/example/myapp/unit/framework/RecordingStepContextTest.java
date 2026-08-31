package com.example.myapp.unit.framework;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.dataflow.AccessEvent;
import com.example.myapp.framework.core.dataflow.DataflowKey;
import com.example.myapp.framework.core.dataflow.DataflowRecorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * StepContext 录制挂钩：访问器读写事件、逃逸口视图与生命周期。
 */
class RecordingStepContextTest {

    private DataflowRecorder recorderWithWindow() {
        DataflowRecorder recorder = DataflowRecorder.external();
        recorder.beginStep("uc1", "step1");
        return recorder;
    }

    @Test
    void typedAccessorsRecordReadsAndWrites() {
        StepContext context = StepContext.standalone();
        DataflowRecorder recorder = recorderWithWindow();
        context.attach(recorder);

        context.setPayload("raw");
        context.putVar("credit", 650);
        context.putBiz("businessId", "u1");
        assertThat(context.getPayload(String.class)).isEqualTo("raw");
        assertThat(context.getVar("credit", Integer.class)).isEqualTo(650);
        assertThat(context.getBiz("businessId")).isEqualTo("u1");

        var events = recorder.snapshot().events();
        assertThat(events).extracting(AccessEvent::key, AccessEvent::op).containsExactly(
                // setPayload → payload 写（payloadType=String）
                tuple(com.example.myapp.framework.core.dataflow.DataflowKey.payload(), AccessEvent.Op.WRITE),
                tuple(DataflowKey.vars("credit"), AccessEvent.Op.WRITE),
                tuple(DataflowKey.biz("businessId"), AccessEvent.Op.WRITE),
                tuple(DataflowKey.payload(), AccessEvent.Op.READ),
                tuple(DataflowKey.vars("credit"), AccessEvent.Op.READ),
                tuple(DataflowKey.biz("businessId"), AccessEvent.Op.READ));
        assertThat(events.get(0).payloadType()).isEqualTo("String");
    }

    @Test
    void storeResultRecordsViaVarOrPayload() {
        StepContext context = StepContext.standalone();
        DataflowRecorder recorder = recorderWithWindow();
        context.attach(recorder);

        context.storeResult("side", "asKey", true);     // as 配置 → vars 写
        context.storeResult("main", null, true);        // 未配 as → payload 写

        var events = recorder.snapshot().events();
        assertThat(events).extracting(AccessEvent::key).containsExactly(
                DataflowKey.vars("asKey"), DataflowKey.payload());
    }

    @Test
    void escapedMapWritesAreRecorded() {
        StepContext context = StepContext.standalone();
        DataflowRecorder recorder = recorderWithWindow();
        context.attach(recorder);

        Map<String, Object> vars = context.getVars();
        vars.put("escaped", "v");                        // 逃逸口直写
        assertThat(vars.get("escaped")).isEqualTo("v");  // 逃逸口直读
        assertThat(context.getVar("escaped")).isEqualTo("v");

        var events = recorder.snapshot().events();
        assertThat(events).extracting(AccessEvent::key, AccessEvent::op).containsExactly(
                tuple(DataflowKey.vars("escaped"), AccessEvent.Op.WRITE),
                tuple(DataflowKey.vars("escaped"), AccessEvent.Op.READ),
                tuple(DataflowKey.vars("escaped"), AccessEvent.Op.READ));
    }

    @Test
    void recordingPreservesReadWriteBehavior() {
        StepContext context = StepContext.standalone();
        DataflowRecorder recorder = recorderWithWindow();
        context.attach(recorder);

        context.putVar("k", 1);
        context.getVars().put("k2", 2);
        context.getVars().remove("k");

        assertThat(context.getVar("k")).isNull();
        assertThat(context.getVar("k2")).isEqualTo(2);
        assertThat(context.getVars()).containsOnlyKeys("k2");
    }

    @Test
    void noRecorderMeansNoBehaviorChange() {
        StepContext context = StepContext.standalone();

        context.setPayload("raw");
        context.putVar("k", 1);

        assertThat(context.recorder()).isNull();
        assertThat(context.getPayload(String.class)).isEqualTo("raw");
        assertThat(context.getVar("k")).isEqualTo(1);
        // 未录制时 getVars 返回内部 Map 本体（行为完全不变）
        Map<String, Object> vars = context.getVars();
        vars.put("direct", 1);
        assertThat(context.getVar("direct")).isEqualTo(1);
    }

    @Test
    void childContextInheritsRecorder() {
        StepContext parent = StepContext.standalone();
        DataflowRecorder recorder = recorderWithWindow();
        parent.attach(recorder);

        StepContext child = parent.newChildContext();
        child.setPayload("child-payload");

        assertThat(child.recorder()).isSameAs(recorder);
        assertThat(recorder.snapshot().events())
                .extracting(AccessEvent::key)
                .contains(DataflowKey.payload());
    }

    @Test
    void detachStopsRecording() {
        StepContext context = StepContext.standalone();
        DataflowRecorder recorder = recorderWithWindow();
        context.attach(recorder);
        context.putVar("a", 1);
        context.detach();
        context.putVar("b", 2);

        var events = recorder.snapshot().events();
        assertThat(events).extracting(AccessEvent::key).containsExactly(DataflowKey.vars("a"));
        assertThat(context.recorder()).isNull();
    }
}
