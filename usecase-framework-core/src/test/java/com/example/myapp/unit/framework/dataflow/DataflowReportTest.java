package com.example.myapp.unit.framework.dataflow;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.myapp.framework.core.dataflow.Dataflow;
import com.example.myapp.framework.core.dataflow.DataflowBook;
import com.example.myapp.framework.core.dataflow.DataflowReport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 声明簿的用例级视图（DataflowReport）：按键反查读写方。
 */
class DataflowReportTest {

    private DataflowBook bookWithSampleUseCase() {
        DataflowBook book = new DataflowBook();
        book.put("uc1", "writer", Dataflow.declaring().reads("biz.businessId").writes("vars.credit").build());
        book.put("uc1", "reader", Dataflow.declaring().reads("vars.credit").writes("payload").build());
        book.put("uc1", "unknown-step", Dataflow.UNKNOWN);
        return book;
    }

    @Test
    void reportExposesStepDeclarations() {
        DataflowReport report = DataflowReport.of("uc1", bookWithSampleUseCase().ofUseCase("uc1"));

        assertThat(report.useCaseId()).isEqualTo("uc1");
        assertThat(report.steps()).containsExactlyInAnyOrder("writer", "reader", "unknown-step");
        assertThat(report.ofStep("writer")).isPresent();
        assertThat(report.ofStep("missing")).isEmpty();
    }

    @Test
    void writersAndReadersOfKey() {
        Map<String, Dataflow> steps = bookWithSampleUseCase().ofUseCase("uc1");
        DataflowReport report = DataflowReport.of("uc1", steps);

        assertThat(report.writersOf("vars.credit")).containsExactly("writer");
        assertThat(report.readersOf("vars.credit")).containsExactly("reader");
        assertThat(report.readersOf("payload")).isEmpty();   // 无人声明读 payload
        assertThat(report.writersOf("vars.nobody")).isEmpty();
    }

    @Test
    void missingUseCaseYieldsEmptyReport() {
        DataflowBook book = new DataflowBook();

        assertThat(book.ofUseCase("ghost")).isEmpty();
    }

    @Test
    void bookEntriesAreReadOnlyViews() {
        DataflowBook book = bookWithSampleUseCase();
        Map<String, Dataflow> steps = book.ofUseCase("uc1");

        assertThatThrownBy(() -> steps.put("hack", Dataflow.UNKNOWN))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(steps).hasSize(3);
    }
}
