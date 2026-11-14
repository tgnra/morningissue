package net.tgnra.morningissue.ai

/** Where the on-device model stands on this phone, as ML Kit reports it (`FeatureStatus`). */
enum class NanoStatus {
    /** Ready to rate. */
    AVAILABLE,

    /** The phone supports it but the model is not on it yet; [OnDeviceAi] fetches it on Wi-Fi while charging. */
    DOWNLOADABLE,

    /** AICore is fetching the model. */
    DOWNLOADING,

    /** Not supported on this phone (no AICore, an unsupported model or Android version), or the check failed. */
    UNAVAILABLE,
}

/** Why a call to the model failed, as far as the caller needs to know. */
enum class NanoFailure {
    /** AICore refuses inference while the app is not in the foreground (`BACKGROUND_USE_BLOCKED`). */
    BACKGROUND_BLOCKED,

    /** A battery quota for this app or the whole phone is used up for now. */
    QUOTA,

    /** AICore is serving someone else; try another time. */
    BUSY,

    /** The model is not (or no longer) on the phone. */
    NOT_AVAILABLE,

    /** Anything else: this request failed, the next may not. */
    OTHER,
}

class NanoException(val failure: NanoFailure, message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/**
 * Gemini Nano as the rest of the app sees it, so that everything but [MlKitNanoModel] can be
 * tested without ML Kit or a phone that has AICore.
 */
interface NanoModel {
    /** Where the model stands. Never throws: a failed check is [NanoStatus.UNAVAILABLE]. */
    suspend fun status(): NanoStatus

    /**
     * The model's reply to [prompt], as text.
     *
     * @throws NanoException when the model refused or failed
     */
    suspend fun generate(prompt: String): String

    /**
     * Fetches the model and returns once it is on the phone (true) or the download failed
     * (false). AICore does the fetching; this only asks for it and waits.
     */
    suspend fun download(): Boolean

    /** For a phone without the model, or a build where it is switched off: unavailable, and every call fails. */
    object Unavailable : NanoModel {
        override suspend fun status() = NanoStatus.UNAVAILABLE
        override suspend fun generate(prompt: String): String = throw NanoException(NanoFailure.NOT_AVAILABLE)
        override suspend fun download() = false
    }
}
