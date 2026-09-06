package com.example.datatransfer.core.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.example.datatransfer.core.TransferSpecRegistry;
import com.example.datatransfer.core.validation.TransferSpecValidator;

/** 自动配置测试：imports 注册生效、classpath 位置经 ResourceLoader 解析并校验装载。 */
class DataTransferAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataTransferAutoConfiguration.class));

    @Test
    void loadsSpecsFromConfiguredClasspathLocations() {
        runner.withPropertyValues("data-transfer.specs[0].location=classpath:specs/valid-order.yaml")
                .run(context -> {
                    assertThat(context).hasSingleBean(TransferSpecValidator.class);
                    assertThat(context).hasSingleBean(TransferSpecRegistry.class);

                    TransferSpecRegistry registry = context.getBean(TransferSpecRegistry.class);
                    assertThat(registry.contains("ECommerce → CRM")).isTrue();
                    // 装载的引擎可用
                    assertThat(registry.engineOf("ECommerce → CRM")).isNotNull();
                });
    }

    @Test
    void disabledLocationIsSkipped() {
        runner.withPropertyValues(
                        "data-transfer.specs[0].location=classpath:specs/valid-order.yaml",
                        "data-transfer.specs[0].enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(TransferSpecRegistry.class);
                    assertThat(context.getBean(TransferSpecRegistry.class).contains("ECommerce → CRM"))
                            .isFalse();
                });
    }

    @Test
    void invalidSpecLocation_failsStartup() {
        runner.withPropertyValues("data-transfer.specs[0].location=classpath:specs/missing.yaml")
                .run(context -> assertThat(context).hasFailed());
    }
}
