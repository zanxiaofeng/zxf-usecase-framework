package com.example.myapp.unit.framework.dataflow;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.myapp.framework.core.dataflow.AccessEvent;
import com.example.myapp.framework.core.dataflow.Dataflow;
import com.example.myapp.framework.core.dataflow.DataflowBook;
import com.example.myapp.framework.core.dataflow.DataflowConformance;
import com.example.myapp.framework.core.dataflow.DataflowKey;
import com.example.myapp.framework.core.dataflow.DataflowTrace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 声明-实测对照检查：未声明写入告警（规则 1）、声明无人读取告警（规则 2）与 INFO 汇总。
 */
class DataflowConformanceTest {

    private DataflowTrace traceOf(AccessEvent... events) {
        return DataflowTrace.of(List.of(events));
    }

    private AccessEvent write(String useCaseId, String step, DataflowKey key) {
        return AccessEvent.of(0, useCaseId, step, AccessEvent.Op.WRITE, key);
    }

    private AccessEvent read(String useCaseId, String step, DataflowKey key) {
        return AccessEvent.of(0, useCaseId, step, AccessEvent.Op.READ, key);
    }

    private DataflowConformance.Finding.Level lastLevel(List<DataflowConformance.Finding> findings) {
        return findings.get(findings.size() - 1).level();
    }

    @Test
    void undeclaredWriteWarns() {
        DataflowBook book = new DataflowBook();
        book.put("uc1", "writer", Dataflow.declaring().writes("vars.credit").build());
        DataflowTrace trace = traceOf(
                write("uc1", "writer", DataflowKey.vars("credit")),
                write("uc1", "writer", DataflowKey.vars("extra")));

        List<DataflowConformance.Finding> findings = DataflowConformance.check(book, trace);

        assertThat(findings).extracting(DataflowConformance.Finding::level)
                .containsExactly(DataflowConformance.Finding.Level.WARN,
                        DataflowConformance.Finding.Level.WARN,
                        DataflowConformance.Finding.Level.INFO);
        assertThat(findings.get(0).message())
                .contains("uc1").contains("writer").contains("vars.extra");
    }

    @Test
    void unknownStepWritesAreExempt() {
        DataflowBook book = new DataflowBook();
        book.put("uc1", "free", Dataflow.UNKNOWN);
        DataflowTrace trace = traceOf(write("uc1", "free", DataflowKey.vars("anything")));

        List<DataflowConformance.Finding> findings = DataflowConformance.check(book, trace);

        assertThat(findings).hasSize(1);
        assertThat(lastLevel(findings)).isEqualTo(DataflowConformance.Finding.Level.INFO);
    }

    @Test
    void wildcardDeclaredWriteCoversAnyVarsKey() {
        DataflowBook book = new DataflowBook();
        book.put("uc1", "dynamic", Dataflow.declaring().writes("vars.*").build());
        DataflowTrace trace = traceOf(
                write("uc1", "dynamic", DataflowKey.vars("runtime-key")),
                read("uc1", "consumer", DataflowKey.vars("runtime-key")));

        List<DataflowConformance.Finding> findings = DataflowConformance.check(book, trace);

        // 规则 1：通配声明覆盖实测键，无未声明告警；规则 2：实测键有人读，无无人读取告警
        assertThat(findings).hasSize(1);
        assertThat(lastLevel(findings)).isEqualTo(DataflowConformance.Finding.Level.INFO);
    }

    @Test
    void wildcardDeclaredWriteWithoutReaderWarnsWithActualKey() {
        DataflowBook book = new DataflowBook();
        book.put("uc1", "dynamic", Dataflow.declaring().writes("vars.*").build());
        DataflowTrace trace = traceOf(write("uc1", "dynamic", DataflowKey.vars("runtime-key")));

        List<DataflowConformance.Finding> findings = DataflowConformance.check(book, trace);

        // 通配声明的无人读取告警对齐到实测键，消息可归因
        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).message()).contains("vars.runtime-key");
    }

    @Test
    void declaredWriteWithoutAnyReaderWarns() {
        DataflowBook book = new DataflowBook();
        book.put("uc1", "writer", Dataflow.declaring().writes("vars.orphan").build());
        DataflowTrace trace = traceOf(write("uc1", "writer", DataflowKey.vars("orphan")));

        List<DataflowConformance.Finding> findings = DataflowConformance.check(book, trace);

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).level()).isEqualTo(DataflowConformance.Finding.Level.WARN);
        assertThat(findings.get(0).message()).contains("vars.orphan").contains("无人读取");
        assertThat(lastLevel(findings)).isEqualTo(DataflowConformance.Finding.Level.INFO);
    }

    @Test
    void declaredWriteReadByNestedChildSatisfiesRule() {
        DataflowBook book = new DataflowBook();
        book.put("parent", "produce", Dataflow.declaring().writes("vars.shared").build());
        book.put("child", "consume", Dataflow.declaring().reads("vars.shared").writes("payload").build());
        DataflowTrace trace = traceOf(
                write("parent", "produce", DataflowKey.vars("shared")),
                read("child", "consume", DataflowKey.vars("shared")));

        List<DataflowConformance.Finding> findings = DataflowConformance.check(book, trace);

        assertThat(findings).hasSize(1);
        assertThat(lastLevel(findings)).isEqualTo(DataflowConformance.Finding.Level.INFO);
    }

    @Test
    void payloadWritesAreExemptFromUnreadRule() {
        DataflowBook book = new DataflowBook();
        book.put("uc1", "writer", Dataflow.declaring().writes("payload").build());
        DataflowTrace trace = traceOf(write("uc1", "writer", DataflowKey.payload()));

        List<DataflowConformance.Finding> findings = DataflowConformance.check(book, trace);

        // payload 消费在响应层（管道外），不参与「无人读取」判定
        assertThat(findings).hasSize(1);
        assertThat(lastLevel(findings)).isEqualTo(DataflowConformance.Finding.Level.INFO);
    }

    @Test
    void wildcardSnapshotReadDoesNotExemptUnreadRule() {
        DataflowBook book = new DataflowBook();
        book.put("uc1", "starter", Dataflow.declaring().writes("biz.businessId").build());
        DataflowTrace trace = traceOf(
                write("uc1", "starter", DataflowKey.biz("businessId")),
                // logging dump 场景：getBiz().toString() 记录 biz.* 通配读——观测而非消费
                read("uc1", "logging", DataflowKey.parse("biz.*")));

        List<DataflowConformance.Finding> findings = DataflowConformance.check(book, trace);

        assertThat(findings.get(0).message()).contains("biz.businessId").contains("无人读取");
    }

    @Test
    void summaryInfoContainsCounters() {
        DataflowBook book = new DataflowBook();
        book.put("uc1", "writer", Dataflow.declaring().writes("vars.orphan").build());
        DataflowTrace trace = traceOf(
                write("uc1", "writer", DataflowKey.vars("orphan")),
                write("uc1", "writer", DataflowKey.vars("extra")));

        List<DataflowConformance.Finding> findings = DataflowConformance.check(book, trace);

        DataflowConformance.Finding summary = findings.get(findings.size() - 1);
        assertThat(summary.level()).isEqualTo(DataflowConformance.Finding.Level.INFO);
        assertThat(summary.message()).contains("recorded accesses=2").contains("undeclared writes=1");
    }
}
