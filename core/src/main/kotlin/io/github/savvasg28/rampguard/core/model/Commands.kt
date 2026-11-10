package io.github.savvasg28.rampguard.core.model

/** Free text from outside (dispatch payloads, the Actions tab, the CLI) is untrusted and is cleaned before use. */
object Sanitise {
    const val MAX_REASON_LENGTH: Int = 200
    const val MAX_SOURCE_LENGTH: Int = 64

    private val controlOrFormat = Regex("[\\p{Cntrl}\\p{Cf}]")
    private val whitespace = Regex("\\s+")

    fun text(
        value: String,
        maxLength: Int,
    ): String =
        value
            .replace(controlOrFormat, " ")
            .replace(whitespace, " ")
            .trim()
            .take(maxLength)

    fun reason(value: String): String = text(value, MAX_REASON_LENGTH).ifBlank { "no reason given" }

    fun source(value: String): String = text(value, MAX_SOURCE_LENGTH).ifBlank { "unknown" }
}

/**
 * Halt the active release in Play, if its version codes include any of [versionCodes]. A list lets a workflow pass
 * the `version-codes` output of a release that ships several APKs or bundles.
 */
data class HaltRequest(
    val versionCodes: List<Long>,
    val reason: String,
    val source: String,
) {
    init {
        require(versionCodes.isNotEmpty()) { "a halt needs at least one versionCode" }
    }

    constructor(versionCode: Long, reason: String, source: String) : this(listOf(versionCode), reason, source)

    fun sanitised(): HaltRequest = copy(reason = Sanitise.reason(reason), source = Sanitise.source(source))
}
