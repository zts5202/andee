package net.kuafuai.andee.brain

import net.kuafuai.andee.config.BrainConfig
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bytes that go to DeepSeek and to MiMo. A wrong `thinking.type` is silent
 * on MiMo: the official default is thinking on, so a body that omits the field
 * thinks even when the setting says off.
 */
class LlmWireTest {

    private val prior = JSONObject()
        .put("role", "assistant")
        .put("content", "ok")
        .put("reasoning_content", "thought")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", "c1")))

    @Test fun mimoDefaultsToThinkingOffAndNamesFlash() {
        val body = LlmClient.chatBody(
            model = BrainConfig.MIMO_MODEL,
            messages = listOf(prior),
            tools = JSONArray(),
            thinkingOn = BrainConfig.mimoThinkingOn(null),
            reasoningEffort = "high",
            provider = BrainConfig.PROVIDER_MIMO,
        )
        assertEquals("mimo-v2.6-flash", body.getString("model"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.getJSONArray("messages").getJSONObject(0).has("reasoning_content"))
    }

    @Test fun mimoThinkingOnHasNoEffortAndKeepsReasoning() {
        val body = LlmClient.chatBody(
            model = BrainConfig.MIMO_MODEL,
            messages = listOf(prior),
            tools = JSONArray(),
            thinkingOn = BrainConfig.mimoThinkingOn("enabled"),
            reasoningEffort = "high",
            provider = BrainConfig.PROVIDER_MIMO,
        )
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertFalse(body.has("reasoning_effort"))
        assertEquals("thought", body.getJSONArray("messages").getJSONObject(0).getString("reasoning_content"))
        val echoed = LlmClient.assistantForHistory(prior, keepReasoning = true)
        assertEquals("thought", echoed.getString("reasoning_content"))
    }

    @Test fun deepseekStillSendsEffortAndDropsReasoning() {
        val on = LlmClient.chatBody(
            model = "deepseek-flash",
            messages = listOf(prior),
            tools = JSONArray(),
            thinkingOn = true,
            reasoningEffort = "high",
            provider = BrainConfig.PROVIDER_DEEPSEEK,
        )
        assertEquals("enabled", on.getJSONObject("thinking").getString("type"))
        assertEquals("high", on.getString("reasoning_effort"))
        assertFalse(on.getJSONArray("messages").getJSONObject(0).has("reasoning_content"))
        assertFalse(LlmClient.assistantForHistory(prior, keepReasoning = false).has("reasoning_content"))

        val off = LlmClient.chatBody(
            model = "deepseek-flash",
            messages = emptyList(),
            tools = JSONArray(),
            thinkingOn = false,
            reasoningEffort = "high",
            provider = BrainConfig.PROVIDER_DEEPSEEK,
        )
        assertEquals("disabled", off.getJSONObject("thinking").getString("type"))
        assertFalse(off.has("reasoning_effort"))
    }

    @Test fun deepseekThinkingRuleIsUnchanged() {
        assertTrue(BrainConfig.deepseekThinkingOn(null, "enabled"))
        assertFalse(BrainConfig.deepseekThinkingOn("disabled", "enabled"))
        assertTrue(BrainConfig.deepseekThinkingOn("enabled", "disabled"))
        // A saved blank is on. Only a missing key uses the build fallback.
        assertTrue(BrainConfig.deepseekThinkingOn("", "disabled"))
        assertFalse(BrainConfig.mimoThinkingOn(null))
        assertFalse(BrainConfig.mimoThinkingOn("disabled"))
        assertFalse(BrainConfig.mimoThinkingOn(""))
        assertTrue(BrainConfig.mimoThinkingOn("enabled"))
        assertEquals(BrainConfig.PROVIDER_DEEPSEEK, BrainConfig.normalizeProvider(null))
        assertEquals(BrainConfig.PROVIDER_MIMO, BrainConfig.normalizeProvider("MiMo"))
    }

    @Test fun mimoSendsApiKeyAndBearerDeepseekSendsBearerOnly() {
        val mimo = Request.Builder().url("https://api.xiaomimimo.com/v1/chat/completions")
        LlmClient.authorize(mimo, BrainConfig.PROVIDER_MIMO, "secret")
        val mimoReq = mimo.build()
        assertEquals("secret", mimoReq.header("api-key"))
        assertEquals("Bearer secret", mimoReq.header("Authorization"))

        val deepseek = Request.Builder().url("https://api.deepseek.com/chat/completions")
        LlmClient.authorize(deepseek, BrainConfig.PROVIDER_DEEPSEEK, "secret")
        val deepseekReq = deepseek.build()
        assertNull(deepseekReq.header("api-key"))
        assertEquals("Bearer secret", deepseekReq.header("Authorization"))
    }
}
