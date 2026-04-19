package com.example.oop.llm

import android.content.Context
import com.example.oop.llm.tools.ToolCallParser
import com.example.oop.llm.tools.ToolExecutor
import com.example.oop.llm.tools.ToolOutcome
import com.example.oop.llm.tools.encodeToolResultJson
import com.example.oop.ontology.Designation
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class ModelNotFoundException(path: String) :
    IllegalStateException("Gemma 4 E4B .litertlm not found at $path")

class LlmInitException(cause: Throwable) : RuntimeException(cause)

sealed interface TurnEvent {
    data class AssistantDelta(val text: String) : TurnEvent

    data class ToolCall(
        val name: String,
        val arguments: JsonObject,
    ) : TurnEvent

    data class ToolResult(
        val json: String,
        val designation: Designation?,
    ) : TurnEvent

    data class Final(
        val text: String,
        val designations: List<Designation>,
    ) : TurnEvent
}

class LiteRtLmEngine(private val context: Context) {
    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var conversation: Conversation? = null

    @Volatile
    var isGpu: Boolean = false
        private set

    @Volatile
    private var systemPromptInstalled: Boolean = false

    val isInitialized: Boolean
        get() = engine != null && conversation != null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) {
            return@withContext
        }

        val modelFile = ModelPaths.resolveExisting(context)
            ?: throw ModelNotFoundException(ModelPaths.downloadedFile(context).absolutePath)

        fun createConfig(backend: Backend): EngineConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = backend,
            visionBackend = Backend.CPU(),
            cacheDir = context.cacheDir.path,
        )

        close()

        try {
            Engine(createConfig(Backend.GPU())).also { candidate ->
                candidate.initialize()
                engine = candidate
            }
            isGpu = true
        } catch (_: Throwable) {
            try {
                Engine(createConfig(Backend.CPU())).also { candidate ->
                    candidate.initialize()
                    engine = candidate
                }
                isGpu = false
            } catch (cpuFailure: Throwable) {
                close()
                throw LlmInitException(cpuFailure)
            }
        }

        conversation = engine?.createConversation()
    }

    fun generate(prompt: String, imageBytes: ByteArray? = null): Flow<String> = flow {
        val activeConversation = conversation ?: error("LiteRtLmEngine not initialized")
        val runningText = StringBuilder()
        activeConversation.sendMessageAsync(buildUserMessage(prompt, imageBytes)).collect { message ->
            runningText.append(extractDelta(message))
            emit(runningText.toString())
        }
    }

    fun generateWithTools(
        prompt: String,
        imageBytes: ByteArray? = null,
        tools: List<ToolExecutor>,
        maxRounds: Int = 4,
    ): Flow<TurnEvent> = flow {
        val activeConversation = conversation ?: error("LiteRtLmEngine not initialized")
        var nextMessage = buildUserMessage(
            prompt = prompt,
            imageBytes = imageBytes,
            promptPrefix = installSystemPrompt(),
        )
        var rounds = 0
        var lastInvocationSignature: String? = null
        val fullText = StringBuilder()
        val designations = LinkedHashMap<String, Designation>()

        while (true) {
            val turnText = StringBuilder()
            activeConversation.sendMessageAsync(nextMessage).collect { message ->
                val delta = extractDelta(message)
                if (delta.isEmpty()) {
                    return@collect
                }
                turnText.append(delta)
                emit(TurnEvent.AssistantDelta(turnText.toString()))
            }
            fullText.append(turnText)

            val invocation = ToolCallParser.firstInvocation(turnText.toString())
            if (invocation == null || rounds >= maxRounds) {
                emit(TurnEvent.Final(fullText.toString(), designations.values.toList()))
                return@flow
            }

            val invocationSignature = "${invocation.name}:${invocation.arguments}"
            if (invocationSignature == lastInvocationSignature) {
                emit(TurnEvent.Final(fullText.toString(), designations.values.toList()))
                return@flow
            }
            lastInvocationSignature = invocationSignature
            rounds += 1

            emit(TurnEvent.ToolCall(invocation.name, invocation.arguments))
            val outcome = tools.firstOrNull { it.name == invocation.name }?.invoke(invocation)
                ?: ToolOutcome.Error("unknown_tool", invocation.name)
            val payload = encodeToolResultJson(outcome)
            val designation = (outcome as? ToolOutcome.Success)?.designation
            if (designation != null) {
                designations[designation.source] = designation
            }
            emit(TurnEvent.ToolResult(payload, designation))
            nextMessage = buildTextMessage("<tool_result>$payload</tool_result>")
        }
    }

    fun reset() {
        val activeEngine = engine ?: return
        runCatching { conversation?.close() }
        conversation = activeEngine.createConversation()
        systemPromptInstalled = false
    }

    fun close() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        isGpu = false
        systemPromptInstalled = false
    }

    private fun installSystemPrompt(): String {
        if (systemPromptInstalled) {
            return ""
        }
        systemPromptInstalled = true
        return buildString {
            append(SYSTEM_PROMPT)
            append("\n\n")
        }
    }

    private fun buildUserMessage(
        prompt: String,
        imageBytes: ByteArray?,
        promptPrefix: String = "",
    ): Message {
        val effectivePrompt = buildString {
            append(promptPrefix)
            append(prompt.ifBlank { "Describe this image." })
        }
        return if (imageBytes != null) {
            Message.user(
                Contents.of(
                    Content.ImageBytes(imageBytes),
                    Content.Text(effectivePrompt),
                ),
            )
        } else {
            buildTextMessage(effectivePrompt)
        }
    }

    private fun buildTextMessage(text: String): Message =
        Message.user(Contents.of(Content.Text(text)))

    private fun extractDelta(message: Message): String =
        message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }

    private companion object {
        const val SYSTEM_PROMPT = """
Analyze the clinical emergency event in this image. If there's a computer screen, analyze the contents inside the screen. For every turn:

1. First, emit ONE short paragraph describing clinically relevant content in the frame.
   If nothing is clinically relevant, say so plainly and stop. Include as much categorical detail in the sentence as possible relating to any of the following categories: Certain infectious and parasitic diseases, Neoplasms, Diseases of the blood and blood-forming organs and certain disorders involving the immune mechanism, Endocrine nutritional and metabolic diseases, Mental behavioral and neurodevelopmental disorders, Diseases of the nervous system, Diseases of the eye and adnexa, Diseases of the ear and mastoid process, Diseases of the circulatory system, Diseases of the respiratory system, Diseases of the digestive system, Diseases of the skin and subcutaneous tissue, Diseases of the musculoskeletal system and connective tissue, Diseases of the genitourinary system, Pregnancy childbirth and the puerperium, Certain conditions originating in the perinatal period, Congenital malformations deformations and chromosomal abnormalities, Symptoms signs and abnormal clinical and laboratory findings not elsewhere classified, Injury poisoning and certain other consequences of external causes, External causes of morbidity, Factors influencing health status and contact with health services, Codes for special purposes

2. Then, for each distinct clinical finding, injury, or condition you named, issue a
   tool_call to the matching ontology and stop generating until the runtime replies.

3. When you have enough designations, emit ONE final line exactly in this shape:
   designations: SNOMED <id> <term>; ICD10 <code> <term>; AIS <code> <label>

Do not invent tool_result content. Omit an ontology if no match was found.

When the user message contains <transcript_delta>...</transcript_delta>, ignore the image-specific instructions above and switch to transcript trigger scanning:

1. Immediately call trigger_lookup with the full transcript delta as the query.
2. After the tool result, do not call any other tool.
3. Treat negated matches as suppressed unless a separate positive match is also present.
4. Treat requiresCorroboration=true matches as provisional until another positive trigger appears in the same delta.
5. Emit exactly one final line in this shape:
   triggers: <id> <category>; ...
   If nothing should fire, emit exactly:
   triggers: none
"""
    }
}
