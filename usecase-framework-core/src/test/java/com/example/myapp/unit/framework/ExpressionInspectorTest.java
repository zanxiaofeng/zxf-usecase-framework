package com.example.myapp.unit.framework;

import org.junit.jupiter.api.Test;

import com.example.myapp.framework.expression.ExpressionInspector;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表达式静态分析（启动期数据流报告的读取侧）：变量形式与根对象属性形式、模板、索引器、字面量。
 */
class ExpressionInspectorTest {

    @Test
    void collectsVariableRootFirstSegment() {
        assertThat(ExpressionInspector.collectReads("#vars.credit.score")).containsExactly("vars.credit");
        assertThat(ExpressionInspector.collectReads("#biz.businessId")).containsExactly("biz.businessId");
        assertThat(ExpressionInspector.collectReads("#payload")).containsExactly("payload");
    }

    @Test
    void collectsTemplateRootPropertyForm() {
        assertThat(ExpressionInspector.collectReads("用户 #{biz.businessId} 信用分: #{vars.credit.score}"))
                .containsExactlyInAnyOrder("biz.businessId", "vars.credit");
    }

    @Test
    void collectsIndexerStaticKey() {
        assertThat(ExpressionInspector.collectReads("#vars['credit'].score")).containsExactly("vars.credit");
    }

    @Test
    void literalAndNullYieldNoReads() {
        assertThat(ExpressionInspector.collectReads("plain-literal")).isEmpty();
        assertThat(ExpressionInspector.collectReads(null)).isEmpty();
    }

    @Test
    void malformedExpressionIsSkipped() {
        assertThat(ExpressionInspector.collectReads("#vars.")).isEmpty();
    }

    @Test
    void beanAndTypeReferencesAreNotDataReads() {
        assertThat(ExpressionInspector.collectReads("@userRepository.getById(#path.id)")).isEmpty();
        assertThat(ExpressionInspector.collectReads("T(java.lang.Math).abs(#payload)")).containsExactly("payload");
    }
}
