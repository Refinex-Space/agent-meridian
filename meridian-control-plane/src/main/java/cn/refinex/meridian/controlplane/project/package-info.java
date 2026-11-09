/**
 * 项目上下文：项目的增删改查（架构设计「租户层级」：项目是隔离与授权的基本单位）。删除项目经进程内事件吊销其 API Key、删除项目级
 * 角色绑定。分 {@code api}、{@code app}、{@code domain}、{@code infra} 四层。
 */
package cn.refinex.meridian.controlplane.project;
