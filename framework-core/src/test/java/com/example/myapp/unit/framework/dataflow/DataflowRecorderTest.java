package com.example.myapp.unit.framework.dataflow;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.myapp.framework.core.dataflow.AccessEvent;
import com.example.myapp.framework.core.dataflow.DataflowKey;
import com.example.myapp.framework.core.dataflow.DataflowRecorder;
import com.example.myapp.framework.core.dataflow.DataflowTrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 录制器：step 窗口栈、事件归属与快照。
 */
class DataflowRecorderTest {

    @Test
    void recordsAccessEventsWithinStepWindow() {
        DataflowRecorder recorder = DataflowRecorder.external();
        recorder.beginStep("uc1", "fetchCredit");
        recorder.recordRead(DataflowKey.biz("businessId"));
        recorder.recordWrite(DataflowKey.vars("credit"));
        recorder.recordPayloadWrite("LinkedHashMap");
        recorder.endStep();

        DataflowTrace trace = recorder.snapshot();

        assertThat(trace.events()).hasSize(3);
        assertThat(trace.events().get(0).useCaseId()).isEqualTo("uc1");
        assertThat(trace.events().get(0).stepName()).isEqualTo("fetchCredit");
        assertThat(trace.events().get(0).op()).isEqualTo(AccessEvent.Op.READ);
        assertThat(trace.events().get(0).key()).isEqualTo(DataflowKey.biz("businessId"));
        assertThat(trace.events().get(1).op()).isEqualTo(AccessEvent.Op.WRITE);
        assertThat(trace.events().get(1).key()).isEqualTo(DataflowKey.vars("credit"));
        assertThat(trace.events().get(2).key()).isEqualTo(DataflowKey.payload());
        assertThat(trace.events().get(2).payloadType()).isEqualTo("LinkedHashMap");
    }

    @Test
    void nestedWindowsAttributeToInnermostStep() {
        DataflowRecorder recorder = DataflowRecorder.external();
        recorder.beginStep("parent", "loadUserBase");
        recorder.recordRead(DataflowKey.biz("businessId"));
        recorder.beginStep("child", "loadUser");
        recorder.recordWrite(DataflowKey.payload());
        recorder.endStep();
        recorder.recordWrite(DataflowKey.payload());
        recorder.endStep();

        List<AccessEvent> events = recorder.snapshot().events();

        assertThat(events).hasSize(3);
        assertThat(events.get(1).useCaseId()).isEqualTo("child");
        assertThat(events.get(1).stepName()).isEqualTo("loadUser");
        // 内层窗口关闭后回落到外层窗口
        assertThat(events.get(2).useCaseId()).isEqualTo("parent");
        assertThat(events.get(2).stepName()).isEqualTo("loadUserBase");
    }

    @Test
    void ignoresAccessOutsideStepWindow() {
        DataflowRecorder recorder = DataflowRecorder.external();

        recorder.recordRead(DataflowKey.payload());
        recorder.recordWrite(DataflowKey.vars("x"));
        recorder.recordPayloadWrite("String");

        assertThat(recorder.snapshot().events()).isEmpty();
        assertThat(recorder.active()).isFalse();
    }

    @Test
    void sequenceIsMonotonicAcrossWindows() {
        DataflowRecorder recorder = DataflowRecorder.external();
        recorder.beginStep("uc", "a");
        recorder.recordRead(DataflowKey.payload());
        recorder.endStep();
        recorder.beginStep("uc", "b");
        recorder.recordRead(DataflowKey.payload());
        recorder.endStep();

        List<AccessEvent> events = recorder.snapshot().events();

        assertThat(events.get(0).sequence()).isLessThan(events.get(1).sequence());
        assertThat(events).extracting(AccessEvent::stepName).containsExactly("a", "b");
    }

    @Test
    void eventsSinceSlicesBySequence() {
        DataflowRecorder recorder = DataflowRecorder.external();
        recorder.beginStep("uc", "a");
        recorder.recordRead(DataflowKey.payload());
        int mark = recorder.eventCount();
        recorder.recordWrite(DataflowKey.vars("x"));
        List<AccessEvent> since = recorder.eventsSince(mark);
        recorder.endStep();

        assertThat(since).hasSize(1);
        assertThat(since.get(0).key()).isEqualTo(DataflowKey.vars("x"));
    }

    @Test
    void snapshotIsDetachedFromLaterRecording() {
        DataflowRecorder recorder = DataflowRecorder.external();
        recorder.beginStep("uc", "a");
        recorder.recordRead(DataflowKey.payload());
        DataflowTrace early = recorder.snapshot();
        recorder.recordWrite(DataflowKey.vars("x"));
        recorder.endStep();

        assertThat(early.events()).hasSize(1);
        assertThat(recorder.snapshot().events()).hasSize(2);
    }

    @Test
    void endStepWithoutBeginThrows() {
        DataflowRecorder recorder = DataflowRecorder.external();

        assertThatThrownBy(recorder::endStep).isInstanceOf(IllegalStateException.class);
    }
}
