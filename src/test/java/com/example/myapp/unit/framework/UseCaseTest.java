package com.example.myapp.unit.framework;

import com.example.myapp.framework.core.DataLoader;
import com.example.myapp.framework.core.DataSaver;
import com.example.myapp.framework.core.DataTransformer;
import com.example.myapp.framework.core.EndpointSpec;
import com.example.myapp.framework.core.SimpleExchangeRequest;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.StepExecutionException;
import com.example.myapp.framework.core.UseCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 核心管道语义：顺序执行、payload 流转、异常包装。
 */
class UseCaseTest {

    @Test
    void executesStepsInOrderAndFlowsPayload() {
        List<String> trace = new ArrayList<>();
        DataLoader loader = context -> context.setPayload("raw");
        DataTransformer transformer = context -> {
            trace.add("transform");
            context.setPayload(context.getPayload(String.class) + "+transformed");
        };
        DataSaver saver = context -> trace.add("save:" + context.getPayload(String.class));

        UseCase useCase = new UseCase("uc1", "demo", new EndpointSpec("GET", "/x", 200),
                List.of(loader, transformer, saver));
        StepContext context = new StepContext(SimpleExchangeRequest.of("GET", "/x"));

        Object result = useCase.execute(context);

        assertThat(result).isEqualTo("raw+transformed");
        assertThat(trace).containsExactly("transform", "save:raw+transformed");
    }

    @Test
    void wrapsStepFailureWithUseCaseIdAndStepName() {
        Step boom = context -> {
            throw new IllegalStateException("boom");
        };
        UseCase useCase = new UseCase("uc2", null, new EndpointSpec("GET", "/x", 200), List.of(boom));
        StepContext context = new StepContext(SimpleExchangeRequest.of("GET", "/x"));

        assertThatThrownBy(() -> useCase.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .satisfies(e -> {
                    StepExecutionException see = (StepExecutionException) e;
                    assertThat(see.getUseCaseId()).isEqualTo("uc2");
                    assertThat(see.getStepName()).isNotBlank();
                    assertThat(see.getCause()).isInstanceOf(IllegalStateException.class);
                });
    }
}
