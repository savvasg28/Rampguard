package io.github.savvasg28.rampguard.core.testing

import io.github.savvasg28.rampguard.core.audit.AuditRenderer
import io.github.savvasg28.rampguard.core.model.HaltRequest
import io.github.savvasg28.rampguard.core.policy.Policy
import io.github.savvasg28.rampguard.core.runner.RolloutRunner
import io.github.savvasg28.rampguard.core.runner.RunOutcome
import java.time.Instant

/**
 * The scenario simulator. Drives a [RolloutRunner] over fake ports with a controllable clock and records every
 * decision on a timeline that tests assert exactly: timestamp and action.
 */
class Simulation(
    val policy: Policy,
    start: Instant = Fixtures.monday9,
    dryRun: Boolean = false,
) {
    val clock = MutableClock(start)
    val play = FakePlayGateway()
    val audit = RecordingAuditLog()
    val logs = mutableListOf<String>()
    val timeline = mutableListOf<String>()

    val runner =
        RolloutRunner(
            policy = policy,
            gateway = play,
            audit = audit,
            clock = clock,
            dryRun = dryRun,
            log = { logs += it },
        )

    val now: Instant get() = clock.now

    /** One scheduled run, like the cron would trigger. */
    suspend fun run(): RunOutcome = runner.tick().also { timeline += entry(it) }

    /** Advance the clock a day and run, like a daily cron. */
    suspend fun nextDay(): RunOutcome {
        clock.advanceHours(24)
        return run()
    }

    suspend fun halt(
        versionCode: Long,
        reason: String = "alert",
        source: String = "datadog",
    ): RunOutcome = halt(listOf(versionCode), reason, source)

    suspend fun halt(
        versionCodes: List<Long>,
        reason: String = "alert",
        source: String = "datadog",
    ): RunOutcome = runner.halt(HaltRequest(versionCodes, reason, source)).also { timeline += entry(it) }

    fun auditText(): String = AuditRenderer.renderAll(audit.records)

    /** Every event of every run so far, as the audit renders them. */
    val events: List<String> get() = audit.records.flatMap { it.events }.map { it.toString() }

    private fun entry(outcome: RunOutcome): String = entry(outcome.record.at, outcome.decision.toString())

    companion object {
        fun entry(
            at: Instant,
            action: String,
        ): String = "$at $action"

        fun entry(
            at: String,
            action: String,
        ): String = entry(Instant.parse(at), action)
    }
}
