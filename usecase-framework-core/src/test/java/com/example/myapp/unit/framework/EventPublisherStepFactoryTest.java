package com.example.myapp.unit.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.core.EventPublisher;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.EventPublisherStepFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * eventPublisher 工厂的装配期发布器校验（fail-fast，不实例化 Bean）：
 * 配置 publisher 时校验存在性与类型；缺省时校验唯一实现（多候选须有 @Primary）。
 */
class EventPublisherStepFactoryTest {

    private final StepExpressionEvaluator evaluator = new StepExpressionEvaluator(null);

    /** 记录发布内容的桩发布器 */
    static final class RecordingPublisher implements EventPublisher {
        final List<Object> published = new ArrayList<>();

        @Override
        public void publish(Object event) {
            published.add(event);
        }
    }

    private StepDefinition definition(Map<String, Object> config) {
        return new StepDefinition("publish", "eventPublisher", null, config).withUseCaseId("testUc");
    }

    private Step createAndExecute(EventPublisherStepFactory factory, Map<String, Object> config) {
        Step step = factory.create(definition(config));
        step.execute(StepContext.standalone());   // 无事务 → 立即发布（同时验证运行期解析）
        return step;
    }

    @Test
    void namedPublisherResolvesAtRuntime() {
        RecordingPublisher publisher = new RecordingPublisher();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("orderEvents", publisher);

        createAndExecute(new EventPublisherStepFactory(beanFactory, evaluator),
                Map.of("event", "'e-1'", "publisher", "orderEvents"));

        assertThat(publisher.published).containsExactly("e-1");
    }

    @Test
    void missingNamedPublisherFailsAtAssembly() {
        EventPublisherStepFactory factory = new EventPublisherStepFactory(new StaticListableBeanFactory(), evaluator);
        assertThatThrownBy(() -> factory.create(definition(Map.of("event", "'e-1'", "publisher", "ghost"))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("publisher bean 'ghost' not found");
    }

    @Test
    void namedPublisherOfWrongTypeFailsAtAssembly() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("notAPublisher", "just-a-string");

        EventPublisherStepFactory factory = new EventPublisherStepFactory(beanFactory, evaluator);
        assertThatThrownBy(() -> factory.create(definition(Map.of("event", "'e-1'", "publisher", "notAPublisher"))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("not an EventPublisher");
    }

    @Test
    void uniquePublisherResolvesWithoutName() {
        RecordingPublisher publisher = new RecordingPublisher();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("theOnlyOne", publisher);

        createAndExecute(new EventPublisherStepFactory(beanFactory, evaluator), Map.of("event", "'e-2'"));

        assertThat(publisher.published).containsExactly("e-2");
    }

    @Test
    void noPublisherAtAllFailsAtAssembly() {
        EventPublisherStepFactory factory = new EventPublisherStepFactory(new StaticListableBeanFactory(), evaluator);
        assertThatThrownBy(() -> factory.create(definition(Map.of("event", "'e-1'"))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("no EventPublisher bean found");
    }

    @Test
    void multiplePublishersWithPrimaryResolveToPrimary() {
        // 多候选 + @Primary：装配期放行，运行期 getIfAvailable 解析到 primary
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("plain",
                BeanDefinitionBuilder.rootBeanDefinition(RecordingPublisher.class).getBeanDefinition());
        beanFactory.registerBeanDefinition("primaryPub",
                BeanDefinitionBuilder.rootBeanDefinition(RecordingPublisher.class).setPrimary(true).getBeanDefinition());

        createAndExecute(new EventPublisherStepFactory(beanFactory, evaluator), Map.of("event", "'e-3'"));

        assertThat(beanFactory.getBean("primaryPub", RecordingPublisher.class).published).containsExactly("e-3");
        assertThat(beanFactory.getBean("plain", RecordingPublisher.class).published).isEmpty();
    }

    @Test
    void multiplePublishersWithoutPrimaryFailAtAssembly() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("first", new RecordingPublisher());
        beanFactory.addBean("second", new RecordingPublisher());

        EventPublisherStepFactory factory = new EventPublisherStepFactory(beanFactory, evaluator);
        assertThatThrownBy(() -> factory.create(definition(Map.of("event", "'e-1'"))))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("without @Primary");
    }
}
