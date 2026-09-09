package com.mooc.backend.service;

/**
 * 窄模型调用接口（change: ai-post-assist，tasks 1.2 / design.md D3）。
 *
 * <p>把 Spring AI {@code ChatClient} 的 builder 链收敛成「给 system + user 一句、回一段文本」，
 * 让上层服务只依赖本接口、零 mock 框架即可用 Fake 覆盖全部解析 / 归一化 / 截断逻辑（零出网）。
 * 同时承担「未装配 → 503」的表达点：{@link ChatClientTextGenerator} 在 {@code ObjectProvider}
 * 为空时抛 {@link com.mooc.backend.exception.AiChatUnavailableException}，调用方无需接触
 * {@code ChatClient}。
 */
public interface AiTextGenerator {

    /**
     * 执行一次非流式文本生成。
     *
     * @param systemPrompt 固定系统提示（编译期常量，防注入）
     * @param userPrompt   用户内容（仅作 user 消息）
     * @return 模型原始文本输出（含可能的前后空白 / 格式噪声，由上层裁剪）
     * @throws com.mooc.backend.exception.AiChatUnavailableException 模型未装配时
     */
    String generate(String systemPrompt, String userPrompt);
}
