package com.example.myapp.framework.steps;

import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;

import com.example.myapp.framework.assemble.StepConfigs;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.core.EventPublisher;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.config.EventPublisherConfig;

/**
 * eventPublisher 步骤工厂。config schema 见 {@link EventPublisherConfig}。
 * 发布器延迟解析（装配期业务 Bean 可能尚未就绪）：
 * 配置 {@code publisher} 时按 Bean 名查找；缺省取容器中唯一的 {@link EventPublisher}，
 * 无实现或多个实现（且无 @Primary）时在首次执行给出明确错误。
 */
@RequiredArgsConstructor
public final class EventPublisherStepFactory implements StepFactory {

    public static final String TYPE = "eventPublisher";

    private final BeanFactory beanFactory;
    private final StepExpressionEvaluator evaluator;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Step create(StepDefinition definition) {
        EventPublisherConfig config = StepConfigs.bind(definition, EventPublisherConfig.class);
        return new EventPublisherStep(definition.nameOr(TYPE), config.event(),
                resolvePublisher(config.publisher()), evaluator);
    }

    private Supplier<EventPublisher> resolvePublisher(String publisherBeanName) {
        if (publisherBeanName != null) {
            return () -> beanFactory.getBean(publisherBeanName, EventPublisher.class);
        }
        return () -> {
            ObjectProvider<EventPublisher> provider = beanFactory.getBeanProvider(EventPublisher.class);
            EventPublisher publisher = provider.getIfUnique();
            if (publisher == null) {
                throw new IllegalStateException(
                        "eventPublisher step requires exactly one EventPublisher bean"
                                + " (none found, or multiple without @Primary); specify config.publisher");
            }
            return publisher;
        };
    }
}
