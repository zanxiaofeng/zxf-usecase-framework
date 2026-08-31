package com.example.myapp.unit.framework.dataflow;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.dataflow.Dataflow;
import com.example.myapp.framework.core.dataflow.DataflowKey;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据流声明：UNKNOWN 语义、builder 与写入覆盖判定。
 */
class DataflowTest {

    @Test
    void unknownMeansNotDeclared() {
        assertThat(Dataflow.UNKNOWN.unknown()).isTrue();
        assertThat(Dataflow.UNKNOWN.reads()).isEmpty();
        assertThat(Dataflow.UNKNOWN.writes()).isEmpty();
    }

    @Test
    void declaringBuildsDeclaredDataflow() {
        Dataflow dataflow = Dataflow.declaring()
                .reads("payload", "vars.credit")
                .writes("payload")
                .build();

        assertThat(dataflow.unknown()).isFalse();
        assertThat(dataflow.reads())
                .containsExactlyInAnyOrder(DataflowKey.payload(), DataflowKey.vars("credit"));
        assertThat(dataflow.writes()).containsExactly(DataflowKey.payload());
    }

    @Test
    void builderAcceptsTypedKeys() {
        Dataflow dataflow = Dataflow.declaring()
                .reads(DataflowKey.vars("encodedUserId"))
                .writes(DataflowKey.vars("result"))
                .build();

        assertThat(dataflow.reads()).containsExactly(DataflowKey.vars("encodedUserId"));
        assertThat(dataflow.writes()).containsExactly(DataflowKey.vars("result"));
    }

    @Test
    void coversExactDeclaredWrite() {
        Dataflow dataflow = Dataflow.declaring().writes("payload", "vars.credit").build();

        assertThat(dataflow.covers(DataflowKey.payload())).isTrue();
        assertThat(dataflow.covers(DataflowKey.vars("credit"))).isTrue();
        assertThat(dataflow.covers(DataflowKey.vars("other"))).isFalse();
    }

    @Test
    void wildcardDeclaredWriteCoversAnyKeyInChannel() {
        Dataflow dataflow = Dataflow.declaring().writes("vars.*").build();

        assertThat(dataflow.covers(DataflowKey.vars("anything"))).isTrue();
        assertThat(dataflow.covers(DataflowKey.biz("anything"))).isFalse();
    }

    @Test
    void unknownCoversNothing() {
        assertThat(Dataflow.UNKNOWN.covers(DataflowKey.payload())).isFalse();
        assertThat(Dataflow.UNKNOWN.covers(DataflowKey.vars("credit"))).isFalse();
    }

    @Test
    void stepDefaultsToUnknownDataflow() {
        Step step = context -> context.setPayload("x");

        assertThat(step.dataflow()).isEqualTo(Dataflow.UNKNOWN);
        assertThat(step.dataflow().unknown()).isTrue();
    }

    @Test
    void setsAreDefensivelyCopied() {
        Set<DataflowKey> mutable = new java.util.HashSet<>();
        mutable.add(DataflowKey.payload());

        Dataflow dataflow = new Dataflow(true, mutable, Set.of());
        mutable.add(DataflowKey.vars("credit"));

        assertThat(dataflow.reads()).containsExactly(DataflowKey.payload());
    }
}
