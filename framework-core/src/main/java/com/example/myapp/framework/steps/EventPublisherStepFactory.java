package com.example.myapp.framework.steps;

import java.util.List;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import com.example.myapp.framework.assemble.StepConfigs;
import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.assemble.StepFactory;
import com.example.myapp.framework.core.EventPublisher;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.config.EventPublisherConfig;

/**
 * eventPublisher 步骤工厂。config schema 见 {@link EventPublisherConfig}。
 *
 * <p>发布器解析分两段：装配期校验（fail-fast，不实例化 Bean）——配置 {@code publisher} 时
 * 校验 Bean 存在且类型匹配；缺省时校验容器存在唯一实现（多个实现须有一个 {@code @Primary}）。
 * 运行期首次执行才真正解析（getIfAvailable 解析 @Primary），避免装配期提前实例化业务 Bean
 * 撞上 Bean 创建循环。</p>
 */
@RequiredArgsConstructor
public final class EventPublisherStepFactory implements StepFactory {

    public static final String TYPE = "eventPublisher";

    private final ListableBeanFactory beanFactory;
    private final StepExpressionEvaluator evaluator;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Step create(StepDefinition definition) {
        EventPublisherConfig config = StepConfigs.bind(definition, EventPublisherConfig.class);
        String name = definition.nameOr(TYPE);
        return new EventPublisherStep(name, config.event(), resolvePublisher(name, config.publisher()), evaluator);
    }

    private Supplier<EventPublisher> resolvePublisher(String stepName, String publisherBeanName) {
        if (publisherBeanName != null) {
            validateNamedPublisher(stepName, publisherBeanName);
            return () -> beanFactory.getBean(publisherBeanName, EventPublisher.class);
        }
        validateUniquePublisher(stepName);
        // getIfAvailable：唯一实现直接返回，多候选时解析 @Primary（多候选无 @Primary 已在装配期拦截）
        return () -> beanFactory.getBeanProvider(EventPublisher.class).getIfAvailable();
    }

    /** 配置 Bean 名时：装配期校验存在性与类型（containsBean / getType 均不实例化） */
    private void validateNamedPublisher(String stepName, String publisherBeanName) {
        if (!beanFactory.containsBean(publisherBeanName)) {
            throw new UseCaseAssemblyException(
                    "step [%s]: publisher bean '%s' not found".formatted(stepName, publisherBeanName));
        }
        Class<?> type = beanFactory.getType(publisherBeanName);
        if (type != null && !EventPublisher.class.isAssignableFrom(type)) {
            throw new UseCaseAssemblyException(
                    "step [%s]: bean '%s' is %s, not an EventPublisher"
                            .formatted(stepName, publisherBeanName, type.getSimpleName()));
        }
    }

    /** 缺省时：装配期校验存在唯一可解析的实现（多候选须有 @Primary），均不实例化 */
    private void validateUniquePublisher(String stepName) {
        String[] names = beanFactory.getBeanNamesForType(EventPublisher.class, false, false);
        if (names.length == 0) {
            throw new UseCaseAssemblyException(
                    "step [%s]: no EventPublisher bean found; register one or specify config.publisher"
                            .formatted(stepName));
        }
        if (names.length > 1 && !hasPrimary(names)) {
            throw new UseCaseAssemblyException(
                    "step [%s]: multiple EventPublisher beans %s without @Primary; specify config.publisher"
                            .formatted(stepName, List.of(names)));
        }
    }

    /** BeanDefinition 只在 ConfigurableListableBeanFactory 上可读；读不到时（罕见）交给运行期解析兜底 */
    private boolean hasPrimary(String[] names) {
        if (!(beanFactory instanceof ConfigurableListableBeanFactory configurable)) {
            return false;
        }
        for (String name : names) {
            if (configurable.getBeanDefinition(name).isPrimary()) {
                return true;
            }
        }
        return false;
    }
}
