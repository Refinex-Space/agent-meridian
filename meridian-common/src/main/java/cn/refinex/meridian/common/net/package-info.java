/**
 * 出站地址的 SSRF 防护（架构设计「平台工具」「凭据」，ADR-0035、ADR-0045）：{@link cn.refinex.meridian.common.net.AddressPolicy}
 * 给地址分类并解析 IP 字面量的全部写法，{@link cn.refinex.meridian.common.net.EndpointPolicy} 校验管理员配置的端点。
 *
 * <p>只依赖 JDK。{@code web_fetch} 在 DNS 解析之后、建立连接之前逐跳判定；配置的端点在写入与读取目录时各判定一次。
 */
package cn.refinex.meridian.common.net;
