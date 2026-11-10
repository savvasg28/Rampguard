package io.github.savvasg28.rampguard.core.engine

import io.github.savvasg28.rampguard.core.model.Decision
import io.github.savvasg28.rampguard.core.model.HaltRequest
import io.github.savvasg28.rampguard.core.model.LifecycleState
import io.github.savvasg28.rampguard.core.model.Observation
import io.github.savvasg28.rampguard.core.model.ReleaseSnapshot
import io.github.savvasg28.rampguard.core.model.RolloutEvent
import io.github.savvasg28.rampguard.core.model.RolloutStatus
import io.github.savvasg28.rampguard.core.policy.Policy

/**
 * The engine's answer for one run.
 *
 * @property stageIndex the configured stage the observed fraction sits at or just above, or `null` without a rollout
 */
data class Verdict(
    val decision: Decision,
    val events: List<RolloutEvent>,
    val notes: List<String>,
    val stageIndex: Int? = null,
)

/**
 * The pure, stateless decision engine. Everything it knows is in the policy and in what this run saw on the production
 * track; it remembers nothing between runs (ADR 0011). A scheduled run moves an in-progress release from the stage its
 * fraction is at to the next configured stage, and completes it at the last. Halted and completed releases are left
 * alone, whoever halted or completed them.
 */
object Engine {
    /** One scheduled run. A chain of guard clauses, on purpose: each early return is a documented exit. */
    @Suppress("ReturnCount")
    fun decide(
        policy: Policy,
        obs: Observation,
    ): Verdict {
        val ctx = Context(policy)
        val playError = obs.playError
        if (playError != null) return ctx.playFailed(playError)

        ctx.notifyLifecycle(obs)
        val active = obs.activeRollout ?: return ctx.noActiveRollout(obs)
        if (active.status == RolloutStatus.HALTED) return ctx.finish(Decision.NoOp(Reasons.halted(active.release)))
        val fraction = active.userFraction ?: return ctx.finish(Decision.NoOp(Reasons.fractionUnknown(active.release)))
        return ctx.step(active, fraction)
    }

    /** The `halt` command. Idempotent: a wrong version code or an already halted release is a no-op. */
    @Suppress("ReturnCount")
    fun decideHalt(
        policy: Policy,
        obs: Observation,
        request: HaltRequest,
    ): Verdict {
        val ctx = Context(policy)
        val playError = obs.playError
        if (playError != null) return ctx.playFailed(playError)
        val active = obs.activeRollout ?: return ctx.finish(Decision.NoOp(Reasons.HALT_IGNORED_NO_ROLLOUT))
        if (request.versionCodes.none(active.release::contains)) {
            return ctx.finish(Decision.NoOp(Reasons.haltIgnoredVersion(request.versionCodes, active.release)))
        }
        if (active.status == RolloutStatus.HALTED) {
            return ctx.finish(Decision.NoOp(Reasons.haltIgnoredAlreadyHalted(active.release)))
        }
        val reason = Reasons.eventHalt(request.source, request.reason)
        ctx.events += RolloutEvent.Halted(active.release, reason, request.source)
        return ctx.finish(Decision.Halt(reason), active.userFraction)
    }

    private class Context(
        val policy: Policy,
    ) {
        val events = mutableListOf<RolloutEvent>()
        val notes = mutableListOf<String>()

        fun finish(
            decision: Decision,
            fraction: Double? = null,
        ): Verdict =
            Verdict(
                decision,
                events.toList(),
                notes.toList(),
                fraction?.let {
                    policy.stageAtOrBelow(it).coerceAtLeast(0)
                },
            )

        fun playFailed(message: String): Verdict {
            events += RolloutEvent.GuardError(Reasons.playUnreadable(message))
            return finish(Decision.NoOp(Reasons.playUnreadable(message)))
        }

        /** Releases waiting for a person are reported on every run that sees them: a daily reminder, not a dedupe. */
        fun notifyLifecycle(obs: Observation) {
            for (snapshot in obs.production) {
                when (snapshot.lifecycle) {
                    LifecycleState.APPROVED_NOT_PUBLISHED -> events += RolloutEvent.ReadyToPublish(snapshot.release)
                    LifecycleState.NOT_APPROVED -> events += RolloutEvent.Rejected(snapshot.release)
                    else -> Unit
                }
            }
        }

        fun noActiveRollout(obs: Observation): Verdict {
            val completed = obs.latestCompleted
            if (completed != null) return finish(Decision.NoOp(Reasons.completed(completed.release)))
            val pending =
                obs.production.maxByOrNull { it.release.maxVersionCode }
                    ?: return finish(Decision.NoOp(Reasons.NO_PRODUCTION_RELEASE))
            val reason =
                when (pending.lifecycle) {
                    LifecycleState.APPROVED_NOT_PUBLISHED -> Reasons.awaitingPublish(pending.release)
                    LifecycleState.NOT_APPROVED -> Reasons.rejected(pending.release)
                    else -> Reasons.inReview(pending.release, pending.lifecycle)
                }
            return finish(Decision.NoOp(reason))
        }

        /** From the stage the fraction is at (or the one just below it) to the next; the last stage completes. */
        fun step(
            active: ReleaseSnapshot,
            fraction: Double,
        ): Verdict {
            val current = policy.stageAtOrBelow(fraction)
            if (!policy.isOnStage(fraction)) {
                notes += Reasons.betweenStages(fraction, policy.stages.getOrNull(current))
            }
            val next = current + 1
            if (next >= policy.lastStageIndex) {
                events += RolloutEvent.Completed(active.release, fraction)
                return finish(Decision.Complete, fraction)
            }
            val toFraction = policy.fractionOf(next)
            events += RolloutEvent.Advanced(active.release, fraction, toFraction)
            return finish(Decision.Advance(next, toFraction), fraction)
        }
    }
}
