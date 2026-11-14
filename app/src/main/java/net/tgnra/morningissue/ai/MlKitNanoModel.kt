package net.tgnra.morningissue.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Gemini Nano through the ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`), which talks
 * to AICore, the system service that holds and runs the model. The only class that touches ML
 * Kit; nothing is created until the first call.
 *
 * AICore runs the model on the phone: the prompt and the reply never leave it. It only serves an
 * app in the foreground: from the background a call fails with `BACKGROUND_USE_BLOCKED` ("Background
 * usage is blocked. Please use the API when your app is in the foreground instead.", in the
 * library's own messages), which is why ratings are made while the app is open and builds only
 * read them.
 *
 * The library's minimum SDK is 26, the app's own, so no API-level guard is needed: on a phone
 * without AICore [status] reports [NanoStatus.UNAVAILABLE] and nothing else is ever called.
 */
class MlKitNanoModel : NanoModel {
    private val client: GenerativeModel by lazy { Generation.getClient() }

    override suspend fun status(): NanoStatus = try {
        when (client.checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> NanoStatus.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
            else -> NanoStatus.UNAVAILABLE
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // No AICore, an AICore too old for this library, or a library that cannot bind to it.
        NanoStatus.UNAVAILABLE
    }

    override suspend fun generate(prompt: String): String {
        val request = generateContentRequest(TextPart(prompt)) {
            // The same answer for the same posts: a rating should not depend on the dice.
            temperature = 0f
            topK = 1
            candidateCount = 1
            // Ten ratings as JSON take about 50 tokens; this leaves room for a model that talks.
            maxOutputTokens = MAX_OUTPUT_TOKENS
        }
        val response = try {
            client.generateContent(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            throw NanoException(failureOf(e.errorCode), e.message, e)
        } catch (e: Exception) {
            throw NanoException(NanoFailure.OTHER, e.message, e)
        }
        return response.candidates.firstOrNull()?.text.orEmpty()
    }

    override suspend fun download(): Boolean = try {
        val end = client.download().first { it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed }
        end is DownloadStatus.DownloadCompleted
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    private fun failureOf(code: Int): NanoFailure = when (code) {
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> NanoFailure.BACKGROUND_BLOCKED
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> NanoFailure.QUOTA
        GenAiException.ErrorCode.BUSY -> NanoFailure.BUSY
        GenAiException.ErrorCode.NOT_AVAILABLE,
        GenAiException.ErrorCode.NOT_SUPPORTED,
        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
        -> NanoFailure.NOT_AVAILABLE
        else -> NanoFailure.OTHER
    }

    private companion object {
        const val MAX_OUTPUT_TOKENS = 160
    }
}
