package com.example.datatransfer.demo;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.example.datatransfer.core.TransferEngine;
import com.example.datatransfer.core.spec.TransferSpec;
import com.example.datatransfer.core.validation.TransferSpecValidator;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * 电商订单 → CRM 全旅程示例（设计文档 §8.10）：core 无 Spring 亦可独立使用的实证——
 * 加载 YAML spec（经 JSON Schema 校验）→ 引擎转换 → pretty print。
 */
public final class OrderTransferDemo {

    public static void main(String[] args) throws Exception {
        // 1. 加载并校验配置（Schema 校验失败即 fail-fast）
        TransferSpec spec;
        try (InputStream in = OrderTransferDemo.class
                .getResourceAsStream("/specs/order-transfer.yaml")) {
            spec = new TransferSpecValidator()
                    .validateAndLoad(in, "specs/order-transfer.yaml");
        }

        // 2. 初始化引擎并执行转换
        String sourceJson = new String(OrderTransferDemo.class
                .getResourceAsStream("/samples/order-001.json").readAllBytes(), StandardCharsets.UTF_8);
        var result = new TransferEngine(spec).transfer(sourceJson);

        // 3. 输出（对照 expected/order-001.json）
        System.out.println(JsonMapper.builder().build()
                .writerWithDefaultPrettyPrinter().writeValueAsString(result));

        // 4.（可选）YAML 视图：spec 本身即 YAML，输出亦可用 YAML mapper 序列化
        System.out.println(YAMLMapper.builder().build()
                .writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private OrderTransferDemo() {
    }
}
