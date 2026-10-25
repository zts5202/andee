package net.kuafuai.andee.config

/**
 * Which brain answers, and how to reach it if it is the local one.
 *
 * Lives in the shared module because both the phone and the desktop run the
 * same brain and need the same answer to "is this a different backend or the
 * same one asked differently". Android still reaches it as
 * `VoiceConfig.Companion.BrainConfig` through a type alias.
 */
data class BrainConfig(
    /** [BRAIN_HUB], [BRAIN_LOCAL] or [BRAIN_CODEFLYING]. */
    val mode: String,
    val apiKey: String,        // empty = on-device brain cannot run
    val baseUrl: String,
    val model: String,
    val thinking: Boolean,
    val reasoningEffort: String,
    /**
     * Which local endpoint this config is aimed at. [PROVIDER_DEEPSEEK] is the
     * free-form OpenAI-compatible row that already existed (DeepSeek by default
     * on a Chinese card). [PROVIDER_MIMO] pins Xiaomi MiMo and its own key.
     */
    val provider: String = PROVIDER_DEEPSEEK,
) {
    /**
     * The agent loop runs on this device (local DeepSeek or CodeFlying
     * — both are "the brain is here", only the backend differs). Only
     * [BRAIN_HUB] is "run elsewhere, listen on a socket".
     */
    val isLocal: Boolean get() = mode == BRAIN_LOCAL || mode == BRAIN_CODEFLYING

    /**
     * Same backend, possibly asked differently. [mode], [apiKey] and
     * [baseUrl] say *who* answers; [model], [thinking] and
     * [reasoningEffort] only say how they are asked, and a running brain
     * can take those on the next request. A config change that is only
     * the second kind must not rebuild the brain, because rebuilding it
     * is what discards the conversation.
     */
    fun sameBackend(other: BrainConfig): Boolean =
        mode == other.mode && apiKey == other.apiKey &&
            baseUrl == other.baseUrl && provider == other.provider

    companion object {
        const val BRAIN_HUB = "hub"
        const val BRAIN_LOCAL = "local"
        const val BRAIN_CODEFLYING = "codeflying"

        /** The existing local row: key, model and URL are the user's. */
        const val PROVIDER_DEEPSEEK = "deepseek"

        /** Xiaomi MiMo. Model and URL are fixed; the key is [mimo_api_key]. */
        const val PROVIDER_MIMO = "mimo"

        const val MIMO_MODEL = "mimo-v2.6-flash"
        const val MIMO_BASE_URL = "https://api.xiaomimimo.com/v1"

        /** Anything other than the literal mimo reads as the existing DeepSeek row. */
        fun normalizeProvider(raw: String?): String =
            if (raw?.trim()?.lowercase() == PROVIDER_MIMO) PROVIDER_MIMO else PROVIDER_DEEPSEEK

        /**
         * MiMo's own default is thinking on. This device must not inherit that:
         * only an explicit `enabled` turns it on. Unset, blank and `disabled`
         * are off.
         */
        fun mimoThinkingOn(saved: String?): Boolean =
            saved?.trim()?.lowercase() == "enabled"

        /**
         * DeepSeek, unchanged: anything other than the literal `disabled` is on.
         * [fallback] is what an unset preference resolves to (`enabled`, unless
         * the build asked for off).
         */
        fun deepseekThinkingOn(saved: String?, fallback: String): Boolean {
            val raw = saved ?: fallback
            return raw.trim().lowercase() != "disabled"
        }
    }
}
