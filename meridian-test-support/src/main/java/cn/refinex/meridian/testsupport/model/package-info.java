/**
 * 脚本化模型服务 {@link cn.refinex.meridian.testsupport.model.ScriptedOpenAiServer}：OpenAI 兼容的本地假供应商，runtime 把它当成普通的
 * OpenAI 兼容供应商调用，端到端测试、控制台的 Playwright 用例与场景预演都默认用它（架构原则第 10 条，测试不依赖真实模型）。
 */
package cn.refinex.meridian.testsupport.model;
