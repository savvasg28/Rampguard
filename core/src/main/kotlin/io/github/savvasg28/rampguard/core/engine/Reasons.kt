package io.github.savvasg28.rampguard.core.engine

import io.github.savvasg28.rampguard.core.model.LifecycleState
import io.github.savvasg28.rampguard.core.model.ReleaseId
import io.github.savvasg28.rampguard.core.model.percent

/** The exact wording of every decision reason. Reasons are part of the audit contract and live in one place. */
object Reasons {
    const val NO_PRODUCTION_RELEASE = "no release on the production track"
    const val HALT_IGNORED_NO_ROLLOUT = "no active rollout to halt"

    fun playUnreadable(message: String) = "Play production track could not be read: $message"

    fun awaitingPublish(release: ReleaseId) = "release $release is approved and waiting to be published"

    fun rejected(release: ReleaseId) = "release $release was not approved"

    fun inReview(
        release: ReleaseId,
        lifecycle: LifecycleState,
    ) = "release $release is $lifecycle; nothing to do"

    fun completed(release: ReleaseId) = "release $release is completed; nothing to do"

    fun halted(release: ReleaseId) = "release $release is halted; a human must resume it in the Play Console"

    fun fractionUnknown(release: ReleaseId) = "user fraction of $release is unknown"

    fun eventHalt(
        source: String,
        reason: String,
    ) = "halt requested by $source: $reason"

    fun haltIgnoredVersion(
        versionCodes: List<Long>,
        active: ReleaseId?,
    ) = (
        versionCodes.singleOrNull()?.let { "versionCode $it is not the active rollout" }
            ?: "none of versionCodes ${versionCodes.joinToString(",")} is the active rollout"
    ) + (active?.let { " ($it)" } ?: "")

    fun haltIgnoredAlreadyHalted(release: ReleaseId) = "release $release is already halted"

    fun betweenStages(
        fraction: Double,
        stagePercent: Int?,
    ) = "fraction ${percent(fraction)} is not a configured stage; treating it as " +
        (stagePercent?.let { "the $it% stage" } ?: "below the first stage")

    fun writeFailed(message: String) = "write to Play failed: $message"
}
