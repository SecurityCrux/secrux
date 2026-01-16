package com.secrux.service

import org.springframework.stereotype.Component

data class AiProviderTemplate(
    val provider: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val regions: List<String>,
    val models: List<String>,
    val docsUrl: String?,
    val description: String?
)

@Component
class AiProviderCatalog {

    private val templates = listOf(
        AiProviderTemplate(
            provider = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            regions = listOf("global"),
            models = listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1-mini", "o3-mini"),
            docsUrl = "https://platform.openai.com/docs/api-reference/introduction",
            description = "Official OpenAI endpoint for GPT-4o/GPT-4.1 models."
        ),
        AiProviderTemplate(
            provider = "azure-openai",
            name = "Azure OpenAI",
            baseUrl = "https://{resource}.openai.azure.com/openai",
            defaultModel = "gpt-4o-mini",
            regions = listOf("global"),
            models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini"),
            docsUrl = "https://learn.microsoft.com/azure/ai-services/openai/how-to",
            description = "Azure managed OpenAI service (replace {resource} with your deployment)."
        ),
        AiProviderTemplate(
            provider = "anthropic",
            name = "Anthropic Claude",
            baseUrl = "https://api.anthropic.com",
            defaultModel = "claude-3-5-sonnet-20240620",
            regions = listOf("global"),
            models = listOf("claude-3-5-sonnet-20240620", "claude-3.5-haiku-20241022", "claude-3-opus-20240229"),
            docsUrl = "https://docs.anthropic.com/claude/reference",
            description = "Claude 3 family for safety-focused reasoning workloads."
        ),
        AiProviderTemplate(
            provider = "google-gemini",
            name = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com",
            defaultModel = "gemini-1.5-pro-latest",
            regions = listOf("global"),
            models = listOf("gemini-1.5-pro-latest", "gemini-1.5-flash-latest", "gemini-1.0-pro"),
            docsUrl = "https://ai.google.dev/gemini-api/docs/get-started",
            description = "REST endpoint for Google's Gemini multi-modal models."
        ),
        AiProviderTemplate(
            provider = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-chat",
            regions = listOf("china"),
            models = listOf("deepseek-chat", "deepseek-coder", "deepseek-reasoner"),
            docsUrl = "https://platform.deepseek.com/api-docs",
            description = "High-performance LLM optimized for Chinese developers."
        ),
        AiProviderTemplate(
            provider = "zhipu",
            name = "智谱清言 (Zhipu)",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4-air",
            regions = listOf("china"),
            models = listOf("glm-4-long", "glm-4-air", "glm-4-flash", "glm-4v-plus"),
            docsUrl = "https://open.bigmodel.cn/dev/api",
            description = "GLM-4 series with stable mainland China latency."
        ),
        AiProviderTemplate(
            provider = "moonshot",
            name = "Moonshot (Kimi)",
            baseUrl = "https://api.moonshot.cn",
            defaultModel = "moonshot-v1-8k",
            regions = listOf("china"),
            models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
            docsUrl = "https://platform.moonshot.cn/docs/api",
            description = "Kimi intelligent assistant APIs with bilingual support."
        ),
        AiProviderTemplate(
            provider = "qwen",
            name = "通义千问 (Qwen)",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen2.5-72b-instruct",
            regions = listOf("china"),
            models = listOf("qwen2.5-72b-instruct", "qwen2.5-32b-instruct", "qwen2.5-coder-lm"),
            docsUrl = "https://help.aliyun.com/zh/dashscope/developer-reference/api",
            description = "Alibaba DashScope compatible endpoint for Qwen family."
        ),
        AiProviderTemplate(
            provider = "ernie",
            name = "百度文心 (ERNIE Bot)",
            baseUrl = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/completions_pro",
            defaultModel = "ernie-4.0",
            regions = listOf("china"),
            models = listOf("ernie-4.0", "ernie-3.5", "ernie-speed-128k"),
            docsUrl = "https://cloud.baidu.com/doc/WENXINWORKSHOP/s/clntwmv7t",
            description = "Baidu ERNIE Bot enterprise endpoint (requires access token)."
        )
    )

    fun listTemplates(): List<AiProviderTemplate> = templates
}
