package com.example.oop.llm.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ToolCallParser {
    private val tagRegex = Regex(
        "<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    fun firstInvocation(buffer: String): ToolInvocation? {
        val match = tagRegex.find(buffer) ?: return null
        return runCatching {
            val payload = ToolJson.parseToJsonElement(match.groupValues[1]).jsonObject
            val name = payload["name"]?.jsonPrimitive?.content ?: return null
            val arguments = payload["arguments"]?.jsonObject ?: buildJsonObject { }
            ToolInvocation(
                name = name,
                arguments = arguments,
                rawTag = match.value,
            )
        }.getOrNull()
    }
}
