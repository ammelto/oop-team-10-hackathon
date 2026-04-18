package com.example.oop.llm

import android.content.Context
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

class ModelNotFoundException(path: String) :
    IllegalStateException("Gemma 4 E4B .litertlm not found at $path")

class LlmInitException(cause: Throwable) : RuntimeException(cause)

class LiteRtLmEngine(private val context: Context) {
    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var conversation: Conversation? = null

    @Volatile
    var isGpu: Boolean = false
        private set

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
        if (imageBytes != null) {
            val fallbackPrompt = prompt.ifBlank { "Describe this image." }
            val promptMessage = Message.user(
                Contents.of(
                    Content.ImageBytes(imageBytes),
                    Content.Text(fallbackPrompt),
                ),
            )
            val runningText = StringBuilder()
            activeConversation.sendMessageAsync(promptMessage).collect { message ->
                val delta = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
                runningText.append(delta)
                emit(runningText.toString())
            }
            return@flow
        }
        val runningText = StringBuilder()
        activeConversation.sendMessageAsync(prompt).collect { token ->
            runningText.append(token)
            emit(runningText.toString())
        }
    }

    fun reset() {
        val activeEngine = engine ?: return
        runCatching { conversation?.close() }
        conversation = activeEngine.createConversation()
    }

    fun close() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        isGpu = false
    }
}
