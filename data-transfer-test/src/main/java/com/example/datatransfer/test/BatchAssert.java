package com.example.datatransfer.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.datatransfer.core.spec.TransferSpec;
import com.example.datatransfer.core.transform.TransformFunction;

/** 批量样本契约测试（设计文档 §10.7）：一个 spec 对多组 fixture/expected，汇总报告后统一判失败。 */
public class BatchAssert {

    private final TransferSpec spec;
    private final List<TestCase> cases = new ArrayList<>();
    private final List<String> ignorePaths = new ArrayList<>();
    private final Map<String, TransformFunction> customFunctions = new HashMap<>();

    public BatchAssert(TransferSpec spec) {
        this.spec = spec;
    }

    public BatchAssert addCase(String fixtureResource, String expectedResource) {
        cases.add(new TestCase(fixtureResource, expectedResource, null, null));
        return this;
    }

    public BatchAssert addCaseJson(String fixtureJson, String expectedJson) {
        cases.add(new TestCase(null, null, fixtureJson, expectedJson));
        return this;
    }

    public BatchAssert ignorePaths(String... paths) {
        this.ignorePaths.addAll(Arrays.asList(paths));
        return this;
    }

    /** 注册自定义变换函数（透传给每个 case 的 {@link TransferAssert}） */
    public BatchAssert registerFunction(String name, TransformFunction function) {
        this.customFunctions.put(name, function);
        return this;
    }

    /** 全部执行；任一失败在汇总报告后抛 AssertionError */
    public void runAll() {
        if (cases.isEmpty()) {
            throw new IllegalStateException(
                    "no test cases added; call addCase()/addCaseJson() before runAll()");
        }
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            TestCase testCase = cases.get(i);
            try {
                TransferAssert assertion = TransferAssert.assertThat(spec);
                if (testCase.fixtureResource() != null) {
                    assertion.withFixture(testCase.fixtureResource());
                } else {
                    assertion.withFixtureJson(testCase.fixtureJson());
                }
                if (!ignorePaths.isEmpty()) {
                    assertion.ignorePaths(ignorePaths.toArray(new String[0]));
                }
                customFunctions.forEach(assertion::registerFunction);
                if (testCase.expectedResource() != null) {
                    assertion.matchesExpected(testCase.expectedResource());
                } else {
                    assertion.matchesExpectedJson(testCase.expectedJson());
                }
            } catch (AssertionError | RuntimeException e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                failures.add("Case #%d (%s): %s".formatted(i, testCase.description(), message));
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("批量契约测试失败 [总计 %d，失败 %d]:\n%s"
                    .formatted(cases.size(), failures.size(), String.join("\n", failures)));
        }
    }

    private record TestCase(String fixtureResource, String expectedResource,
                            String fixtureJson, String expectedJson) {

        String description() {
            return fixtureResource != null ? fixtureResource : "inline-case";
        }
    }
}
