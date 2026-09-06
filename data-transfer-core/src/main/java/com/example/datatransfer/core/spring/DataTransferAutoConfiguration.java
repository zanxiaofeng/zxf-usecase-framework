package com.example.datatransfer.core.spring;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import com.example.datatransfer.core.TransferSpecRegistry;
import com.example.datatransfer.core.spec.TransferSpec;
import com.example.datatransfer.core.validation.TransferSpecValidator;

/**
 * Spring Boot 4 自动配置（设计文档 §8.9）：classpath 位置经 ResourceLoader 解析
 * （不能直接 Path.of()）；校验失败即启动 fail-fast。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "data-transfer", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DataTransferProperties.class)
public class DataTransferAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TransferSpecValidator transferSpecValidator() {
        return new TransferSpecValidator();
    }

    @Bean
    public TransferSpecRegistry transferSpecRegistry(
            DataTransferProperties properties,
            TransferSpecValidator validator,
            ResourceLoader resourceLoader) throws IOException {

        TransferSpecRegistry registry = new TransferSpecRegistry();
        for (DataTransferProperties.SpecLocation location : properties.getSpecs()) {
            if (!location.isEnabled()) {
                continue;
            }
            Resource resource = resourceLoader.getResource(location.getLocation());
            try (InputStream in = resource.getInputStream()) {
                TransferSpec spec = validator.validateAndLoad(in, location.getLocation());
                registry.register(spec);
            }
        }
        return registry;
    }
}
