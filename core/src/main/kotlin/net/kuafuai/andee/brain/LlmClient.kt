package net.kuafuai.andee.brain

import net.kuafuai.andee.config.BrainConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A non-2xx answer from the backend. A type of its own so the status code
 * survives to [LocalBrain], which has a `Context` and can turn it into a
 * sentence the user can act on; this class has none and must not grow one.
 * The message stays `LLM HTTP <code>: <body>` so logs read as they always did.
 */
class LlmHttpException(val code: Int, val body: String) :
    IllegalStateException("LLM HTTP $code: ${body.take(600)}") {

    /**
     * The gateway's own words for what it disliked, without the JSON around
     * them. Falls back to the raw body for gateways that don't follow the
     * `{"error":{"message":…}}` shape.
     */
    val detail: String
        get() = runCatching {
            val err = JSONObject(body).opt("error")
            when (err) {
                is JSONObject -> err.optString("message")
                is String -> err
                else -> ""
            }
        }.getOrDefault("").ifBlank { body }.trim()
}

/**
 * One blocking call to an OpenAI-compatible `/chat/completions`.
 *
 * DeepSeek is the original target. Xiaomi MiMo uses the same call with three
 * differences, all of them on the wire rather than in the loop: the model and
 * URL are fixed, the key travels as `api-key` as well as Bearer, and when
 * thinking is on an assistant message that called a tool must be echoed with
 * its `reasoning_content` or MiMo answers 400. DeepSeek rejects that field, so
 * it is still stripped on the DeepSeek path.
 *
 * Deliberately thin and deliberately **not** streaming. Streaming would let TTS
 * start talking a second or two earlier, but it costs an SSE parser plus
 * incremental `tool_calls` reassembly (arguments arrive as string fragments
 * across deltas), and none of that is needed to make the local brain work.
 *
 * Two shapes in here are easy to get wrong, and both are silent when wrong:
 *
 *  * **`tool_calls[].function.arguments` is a JSON *string*, not an object.**
 *    It has to be re-parsed, and a model that emits malformed JSON must not
 *    take the turn down — see [ToolCall.argumentsOrNull].
 *  * **`reasoning_content` is not one rule.** DeepSeek rejects requests whose
 *    assistant messages carry it, so on that path [Reply.reasoning] is logged
 *    and dropped. MiMo, with thinking on, requires it back on any assistant
 *    message that contains tool calls — see [assistantForHistory].
 *
 * Threading: [complete] blocks the calling thread. It is only ever called from
 * [LocalBrain]'s single loop thread.
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    model: String,
    thinking: Boolean,
    reasoningEffort: String,
    /** [BrainConfig.PROVIDER_DEEPSEEK] or [BrainConfig.PROVIDER_MIMO]. */
    private val provider: String = BrainConfig.PROVIDER_DEEPSEEK,
) {

    // Request parameters, read once per [complete]. Volatile because [retune]
    // lands from the main thread while the loop thread may be mid-turn; the turn
    // in flight keeps what it already sent and the next request carries the new.
    @Volatile private var model = model
    @Volatile private var thinking = thinking
    @Volatile private var reasoningEffort = reasoningEffort

    /**
     * Change what the next request asks for, without touching the endpoint or
     * the key. Those two are the identity of the backend; these three are only
     * how it is asked, so a settings change to them has no reason to cost the
     * user their conversation.
     */
    fun retune(model: String, thinking: Boolean, reasoningEffort: String) {
        this.model = model
        this.thinking = thinking
        this.reasoningEffort = reasoningEffort
    }

    /**
     * Read timeout is generous because a thinking model answers in minutes, not
     * seconds, and a premature timeout here reads to the user as the ball going
     * quiet forever. Connect timeout stays short: a tablet that has lost its
     * network should say so quickly.
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The in-flight call, so [cancel] can cut a turn short. */
    @Volatile
    private var current: okhttp3.Call? = null

    data class ToolCall(
        val id: String,
        val name: String,
        /** Raw JSON text as the model wrote it. */
        val argumentsRaw: String,
    ) {
        /**
         * Null when the model wrote something that isn't a JSON object. The
         * caller turns that into a tool-error message rather than an exception:
         * a model that miswrote its arguments can fix them on the next
         * iteration, and killing the turn denies it the chance.
         */
        fun argumentsOrNull(): JSONObject? =
            if (argumentsRaw.isBlank()) JSONObject()
            else runCatching { JSONObject(argumentsRaw) }.getOrNull()

        /** Identity for the repeated-call guard in [LocalBrain]. */
        fun signature(): String = "$name/$argumentsRaw"
    }

    data class Reply(
        /** Assistant prose. Narration when [toolCalls] is non-empty, otherwise the answer. */
        val content: String,
        /** Logged only — see the class KDoc. */
        val reasoning: String,
        val toolCalls: List<ToolCall>,
        /**
         * The assistant message exactly as it must be echoed back into the
         * history. Kept verbatim from the response rather than rebuilt, minus
         * `reasoning_content`, so no field the API cares about gets lost in
         * translation.
         */
        val assistantMessage: JSONObject,
        val promptTokens: Int,
        val completionTokens: Int,
        val cachedTokens: Int,
    )

    /**
     * @param messages the full conversation, system message first.
     * @param tools OpenAI-format function specs — see [toolSpecs].
     * @param noThinking ask without reasoning whatever the user's setting is.
     *   The log line from [requestLine] uses the same rule, so it names the
     *   `thinking.type` that actually goes out.
     */
    fun requestLine(noThinking: Boolean = false): String {
        val type = if (thinking && !noThinking) "enabled" else "disabled"
        return "llm request model=$model thinking.type=$type provider=$provider"
    }

    fun complete(messages: List<JSONObject>, tools: JSONArray, noThinking: Boolean = false): Reply {
        val thinkingOn = thinking && !noThinking
        val body = chatBody(
            model = model,
            messages = messages,
            tools = tools,
            thinkingOn = thinkingOn,
            reasoningEffort = reasoningEffort,
            provider = provider,
        )

        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Content-Type", "application/json")
        authorize(req, provider, apiKey)
        req.post(body.toString().toRequestBody(JSON))
        val built = req.build()

        val call = http.newCall(built)
        current = call
        val text = try {
            call.execute().use { resp ->
                // Read the body either way: DeepSeek puts the useful part of an
                // error (bad key, unknown model, context overflow) in the body,
                // and a bare "HTTP 400" sends whoever is debugging to the wrong
                // place entirely.
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw LlmHttpException(resp.code, raw)
                }
                raw
            }
        } finally {
            current = null
        }

        val root = JSONObject(text)
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalStateException("LLM reply has no choices: ${text.take(400)}")
        val msg = choice.optJSONObject("message")
            ?: throw IllegalStateException("LLM choice has no message: ${text.take(400)}")

        val calls = mutableListOf<ToolCall>()
        msg.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val tc = arr.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                calls += ToolCall(
                    // A gateway that omits the id would otherwise produce tool
                    // messages the API can't match to their call. Index is a
                    // stable stand-in within one reply, which is the only scope
                    // that matters.
                    id = tc.optString("id").ifEmpty { "call_$i" },
                    name = fn.optString("name"),
                    argumentsRaw = fn.optString("arguments"),
                )
            }
        }

        val usage = root.optJSONObject("usage")
        return Reply(
            content = msg.optString("content").orEmpty(),
            reasoning = msg.optString("reasoning_content").orEmpty(),
            toolCalls = calls,
            assistantMessage = assistantForHistory(msg, keepReasoning = thinkingOn && provider == BrainConfig.PROVIDER_MIMO),
            promptTokens = usage?.optInt("prompt_tokens") ?: 0,
            completionTokens = usage?.optInt("completion_tokens") ?: 0,
            // 命中缓存的 prompt 有多少。火山方舟（现在的上游）把它放在
            // usage.prompt_tokens_details.cached_tokens 里，扁平的
            // prompt_cache_hit_tokens 是 DeepSeek 自家 API 的字段 —— 方舟不发。
            // optInt 取不到就是 0，所以之前每一行日志都写 cached 0，
            // 看着像缓存没生效，其实是根本没读对地方。
            // 两个都认，以后换上游不用再动这里。
            cachedTokens = usage?.let { u ->
                // optInt 对「key 不存在」和「值是 0」返回的一样，所以先 has 再取 ——
                // 否则嵌套块在但缺 cached_tokens 时不会回落到扁平字段。
                val details = u.optJSONObject("prompt_tokens_details")
                if (details != null && details.has("cached_tokens")) details.optInt("cached_tokens")
                else u.optInt("prompt_cache_hit_tokens")
            } ?: 0,
        )
    }

    /**
     * Abort whatever is in flight. Called from the main thread when the user
     * hits ■, so it must not block — [okhttp3.Call.cancel] doesn't.
     */
    fun cancel() {
        val c = current ?: return
        runCatching { c.cancel() }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * The JSON body of one `/chat/completions` call.
         *
         * `thinking.type` is always written. MiMo's own default is thinking on,
         * so leaving the field out would turn it on; DeepSeek already received
         * the field either way. `reasoning_effort` is DeepSeek's knob and is
         * not a MiMo parameter — MiMo only has enabled / disabled.
         *
         * MiMo with thinking on keeps `reasoning_content` on the messages it is
         * handed. Every other call strips it, because DeepSeek rejects the field.
         */
        fun chatBody(
            model: String,
            messages: List<JSONObject>,
            tools: JSONArray,
            thinkingOn: Boolean,
            reasoningEffort: String,
            provider: String,
        ): JSONObject {
            val keepReasoning = thinkingOn && provider == BrainConfig.PROVIDER_MIMO
            val msgArr = JSONArray()
            for (m in messages) msgArr.put(wireMessage(m, keepReasoning))
            val body = JSONObject()
                .put("model", model)
                .put("messages", msgArr)
                .put("stream", false)
            if (tools.length() > 0) {
                body.put("tools", tools).put("tool_choice", "auto")
            }
            body.put("thinking", JSONObject().put("type", if (thinkingOn) "enabled" else "disabled"))
            if (thinkingOn && provider != BrainConfig.PROVIDER_MIMO) {
                body.put("reasoning_effort", reasoningEffort)
            }
            return body
        }

        /**
         * Auth headers. DeepSeek stays `Authorization: Bearer`, as before.
         * MiMo's docs show the OpenAI SDK, which sends Bearer, and also accept
         * `api-key` — both go out, so either reading of the spec works.
         */
        fun authorize(builder: Request.Builder, provider: String, apiKey: String) {
            if (provider == BrainConfig.PROVIDER_MIMO) {
                builder.header("api-key", apiKey)
            }
            builder.header("Authorization", "Bearer $apiKey")
        }

        /**
         * The assistant message to put back into the history.
         *
         * `content` is always present: the API wants the key even when the turn
         * was only tool calls. `reasoning_content` is kept only for MiMo with
         * thinking on — a later tool round without it is a 400.
         */
        fun assistantForHistory(msg: JSONObject, keepReasoning: Boolean): JSONObject {
            val out = JSONObject()
            out.put("role", "assistant")
            val content = msg.optString("content").orEmpty()
            out.put("content", if (content.isEmpty()) JSONObject.NULL else content)
            msg.optJSONArray("tool_calls")?.let { if (it.length() > 0) out.put("tool_calls", it) }
            if (keepReasoning) {
                val reasoning = msg.optString("reasoning_content")
                if (reasoning.isNotEmpty()) out.put("reasoning_content", reasoning)
            }
            return out
        }

        private fun wireMessage(m: JSONObject, keepReasoning: Boolean): JSONObject {
            if (keepReasoning || !m.has("reasoning_content")) return m
            val copy = JSONObject(m.toString())
            copy.remove("reasoning_content")
            return copy
        }

        /**
         * Turn the body's own schema list into OpenAI function specs.
         *
         * [net.kuafuai.andee.net.ToolSchemas] already emits
         * `{name, description, parameters}` with OpenAI's JSON Schema dialect —
         * the only differences are the wrapper and the `method` field, which is
         * this device's internal routing key and means nothing to the model.
         */
        fun toolSpecs(vararg schemaLists: JSONArray): JSONArray {
            val out = JSONArray()
            for (list in schemaLists) {
                for (i in 0 until list.length()) {
                    val t = list.optJSONObject(i) ?: continue
                    val fn = JSONObject()
                        .put("name", t.optString("name"))
                        .put("description", t.optString("description"))
                        .put(
                            "parameters",
                            t.optJSONObject("parameters")
                                ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                        )
                    out.put(JSONObject().put("type", "function").put("function", fn))
                }
            }
            return out
        }

        /**
         * A vision content block carrying an inline image.
         *
         * The one shape in the local brain that is not verified against
         * anything in this repo — it is the OpenAI-compatible form, which is
         * what the rest of this endpoint follows. Isolated into one function on
         * purpose: if DeepSeek wants something else, this is the only edit.
         */
        fun imageBlock(base64: String, mime: String = "image/png"): JSONObject =
            JSONObject()
                .put("type", "image_url")
                .put(
                    "image_url",
                    JSONObject().put("url", "data:$mime;base64,$base64"),
                )

        fun textBlock(text: String): JSONObject =
            JSONObject().put("type", "text").put("text", text)
    }
}
