/**
 * 数据链显性化：键级血缘的声明模型、运行期录制与声明-实测对照检查。
 *
 * <ul>
 *   <li><b>声明（应然）</b>——{@link com.example.myapp.framework.core.dataflow.Dataflow}：
 *       step 经 {@code Step#dataflow()} 声明读写契约，内置 step 由装配器从配置推导</li>
 *   <li><b>实测（实然）</b>——{@link com.example.myapp.framework.core.dataflow.DataflowTrace}：
 *       {@code usecase.dataflow.record=true} 时录制每步对 payload/vars/biz 的键级读写</li>
 *   <li><b>对照</b>——{@link com.example.myapp.framework.core.dataflow.DataflowConformance}：
 *       未声明写入 / 声明无人读取告警</li>
 * </ul>
 *
 * <p>隐私基线：只记录键名与 payload 类型名，永不记录值。</p>
 */
@org.jspecify.annotations.NullMarked
package com.example.myapp.framework.core.dataflow;
