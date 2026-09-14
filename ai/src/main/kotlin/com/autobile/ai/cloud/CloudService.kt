package com.autobile.ai.cloud

/**
 * A cloud service a user can choose, with everything needed to reach it.
 *
 * Presets exist so that turning on cloud assistance is picking a name and pasting a key,
 * rather than knowing a base URL, a header convention and two model identifiers. Each
 * field stays overridable underneath for anyone pointing at a compatible deployment of
 * their own.
 */
enum class CloudService(
    val displayName: String,
    val dialect: CloudDialect,
    val endpoint: String,
    val lightModel: String,
    val advancedModel: String,
    /** Where a person goes to get a key, shown next to the field that wants one. */
    val credentialUrl: String,
    /** Whether this service can be sent a screenshot at all. */
    val supportsImages: Boolean = true,
    /** Whether the user proves entitlement with a key or with a browser sign-in. */
    val authMethod: CloudAuthMethod = CloudAuthMethod.API_KEY,
) {
    GEMINI(
        displayName = "Google Gemini",
        dialect = CloudDialect.GEMINI,
        endpoint = "https://generativelanguage.googleapis.com/v1beta",
        lightModel = "gemini-2.5-flash-lite",
        advancedModel = "gemini-2.5-flash",
        credentialUrl = "https://aistudio.google.com/apikey",
    ),

    OPENAI(
        displayName = "OpenAI",
        dialect = CloudDialect.OPENAI,
        endpoint = "https://api.openai.com/v1",
        lightModel = "gpt-5-mini",
        advancedModel = "gpt-5",
        credentialUrl = "https://platform.openai.com/api-keys",
    ),

    /**
     * A ChatGPT subscription, signed in to rather than keyed.
     *
     * The person who signs in is the person billed, on their own plan, from their own
     * device. Listed separately from [OPENAI] because the two are different purchases:
     * one spends a monthly subscription, the other spends an API balance, and someone
     * holding one does not necessarily hold the other.
     */
    CHATGPT(
        displayName = "ChatGPT subscription",
        dialect = CloudDialect.CHATGPT,
        endpoint = "https://chatgpt.com/backend-api/codex",
        // Both read pictures. The small one classifies, the large one reasons about a
        // screen and repairs a broken step, which is the split the router already makes.
        lightModel = "gpt-5.4-mini",
        advancedModel = "gpt-5.4",
        credentialUrl = "",
        authMethod = CloudAuthMethod.SIGN_IN,
    ),

    CLAUDE(
        displayName = "Anthropic Claude",
        dialect = CloudDialect.ANTHROPIC,
        endpoint = "https://api.anthropic.com/v1",
        lightModel = "claude-haiku-4-5-20251001",
        advancedModel = "claude-sonnet-5",
        credentialUrl = "https://console.anthropic.com/settings/keys",
    ),

    DEEPSEEK(
        displayName = "DeepSeek",
        dialect = CloudDialect.OPENAI,
        endpoint = "https://api.deepseek.com/v1",
        lightModel = "deepseek-chat",
        advancedModel = "deepseek-reasoner",
        credentialUrl = "https://platform.deepseek.com/api_keys",
        // Text only. Offering the screenshot switch here would promise something the
        // service cannot do, and the run would fail at the rung that needs to look.
        supportsImages = false,
    ),

    /** A compatible endpoint the user configures entirely themselves. */
    CUSTOM(
        displayName = "Custom endpoint",
        dialect = CloudDialect.OPENAI,
        endpoint = "",
        lightModel = "",
        advancedModel = "",
        credentialUrl = "",
    );

    companion object {
        fun from(name: String?): CloudService =
            entries.firstOrNull { it.name == name } ?: GEMINI
    }
}
