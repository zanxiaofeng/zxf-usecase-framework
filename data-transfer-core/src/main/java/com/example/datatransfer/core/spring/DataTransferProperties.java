package com.example.datatransfer.core.spring;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

/** 自动配置属性（设计文档 §8.9；前缀 data-transfer，不占用 spring. 保留命名空间）；绑定后启动期校验 fail-fast。 */
@Data
@Validated
@ConfigurationProperties(prefix = "data-transfer")
public class DataTransferProperties {

    /** 是否启用自动装配，默认 true */
    private boolean enabled = true;

    /** Spec 配置位置列表 */
    private List<SpecLocation> specs = new ArrayList<>();

    @Data
    public static class SpecLocation {

        /** 资源位置，支持 classpath: / file: 等前缀（经 ResourceLoader 解析） */
        @NotBlank(message = "data-transfer.specs[].location must not be blank")
        private String location;

        /** 是否启用该份 spec */
        private boolean enabled = true;
    }
}
