package com.example.myapp.framework.steps;

import java.io.InputStream;
import java.util.Objects;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.ObjectMapper;

import com.example.datatransfer.core.TransferEngine;
import com.example.datatransfer.core.exception.TransferException;
import com.example.datatransfer.core.spec.TransferSpec;
import com.example.datatransfer.core.validation.TransferSpecValidator;
import com.example.myapp.framework.assemble.StepConfigs;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.config.DataTransferStepConfig;

/**
 * dataTransfer 步骤工厂：装配期加载 config.spec 指向的 TransferSpec
 * （classpath 资源，可带 {@code classpath:} 前缀）→ JSON Schema 校验（fail-fast，
 * 报错定位 step 名）→ 构建 {@link TransferEngine}（每 step 一个实例）。
 *
 * <p>spec 含第一版未支持的特性（sources / rewrites）时引擎构造期抛出，此处统一
 * 包装为 {@link UseCaseAssemblyException}（装配期 fail-fast，启动即拒绝）。</p>
 */
@RequiredArgsConstructor
public final class DataTransferStepFactory implements StepFactory {

    public static final String TYPE = "dataTransfer";

    private static final String CLASSPATH_PREFIX = "classpath:";

    private final StepExpressionEvaluator evaluator;
    private final ObjectMapper objectMapper;
    private final TransferSpecValidator validator;

    public DataTransferStepFactory(StepExpressionEvaluator evaluator, ObjectMapper objectMapper) {
        this(evaluator, objectMapper, new TransferSpecValidator());
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Step create(StepDefinition definition) {
        DataTransferStepConfig config = StepConfigs.bind(definition, DataTransferStepConfig.class);
        String name = definition.nameOr(TYPE);

        TransferSpec spec = loadSpec(config.getSpec(), name);
        TransferEngine engine;
        try {
            engine = new TransferEngine(spec);
        } catch (TransferException e) {
            // 构造期语义错误（未支持特性/通配数量/strictMode 重复目标等）统一包装为装配失败
            throw new UseCaseAssemblyException(
                    "step [%s]: TransferSpec '%s' rejected: %s".formatted(name, config.getSpec(), e.getMessage()), e);
        }
        return new DataTransferStep(name, engine, config.getSource(), config.getAs(), evaluator, objectMapper);
    }

    private TransferSpec loadSpec(String location, String stepName) {
        String resource = location.startsWith(CLASSPATH_PREFIX)
                ? location.substring(CLASSPATH_PREFIX.length())
                : location;
        try (InputStream in = DataTransferStepFactory.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new UseCaseAssemblyException(
                        "step [%s]: TransferSpec not found on classpath: %s".formatted(stepName, location));
            }
            return validator.validateAndLoad(in, location);
        } catch (UseCaseAssemblyException e) {
            throw e;
        } catch (Exception e) {
            throw new UseCaseAssemblyException(
                    "step [%s]: invalid TransferSpec '%s': %s".formatted(stepName, location, e.getMessage()), e);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DataTransferStepFactory;   // 同类型即等价（UseCaseAssembler 重复 type 检测用）
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(TYPE);
    }
}
