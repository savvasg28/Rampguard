package io.github.savvasg28.rampguard.cli

import io.github.savvasg28.rampguard.core.audit.AuditRenderer
import io.github.savvasg28.rampguard.core.model.Decision
import io.github.savvasg28.rampguard.core.model.DecisionRecord
import io.github.savvasg28.rampguard.core.model.RolloutEvent
import io.github.savvasg28.rampguard.core.model.percent
import io.github.savvasg28.rampguard.core.ports.AuditLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** The audit record on stdout, as the job log. */
class StdoutAuditLog(
    private val out: (String) -> Unit,
) : AuditLog {
    override suspend fun record(record: DecisionRecord) = out(AuditRenderer.render(record).trimEnd())
}

/**
 * Appends the audit record to the GitHub Actions job summary (the file named by `GITHUB_STEP_SUMMARY`), so each run
 * shows its decision in the Actions tab. Does nothing when the variable is absent, e.g. on a laptop.
 */
class JobSummaryAuditLog(
    private val summaryFile: Path?,
) : AuditLog {
    override suspend fun record(record: DecisionRecord) {
        val path = summaryFile ?: return
        val text = "```\n" + AuditRenderer.render(record) + "```\n"
        Files.writeString(path, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}

/**
 * Publishes the record as GitHub Actions step outputs (the file named by `GITHUB_OUTPUT`), so later workflow steps
 * can act on it: `if: steps.rampguard.outputs.event == 'advanced'`. Does nothing when the variable is absent.
 *
 * The outputs come in three layers. `summary` is one ready-made line for a chat message. The flat outputs carry each
 * value on its own for `if:` conditions and tool inputs. `record` is the whole run as one JSON object, for steps that
 * want everything through `fromJSON()`.
 *
 * The output names, and the field names inside `record`, are a contract for workflows: add, never rename. Every
 * output is always written, as an empty string when it does not apply, so an expression never sees an undefined value.
 *
 * | Output | Value |
 * |---|---|
 * | `summary` | One line saying what the run did, including a dry run. |
 * | `event` | What the run did: `advanced`, `completed`, `halted`, `failed` or `none`. A dry run reports `none`. |
 * | `release-name`, `version-codes` | The live release, when there is one. Version codes are comma-separated. |
 * | `from-percent`, `to-percent` | The step taken, on `advanced` and `completed`. |
 * | `reason`, `source` | Why and who, on `halted`. |
 * | `ready-to-publish` | Name of a release approved by review that a person has to publish. |
 * | `rejected` | Name of a release rejected by review. |
 * | `record` | The flat outputs as typed JSON, plus `app`, `command`, `at`, `dry-run`, `decision` and `notes`. |
 */
class StepOutputs(
    private val outputFile: Path?,
) : AuditLog {
    override suspend fun record(record: DecisionRecord) {
        val path = outputFile ?: return
        val lines =
            of(
                record,
            ).entries.joinToString("") { (name, value) -> "$name=${value.lineSequence().joinToString(" ")}\n" }
        Files.writeString(path, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    companion object {
        val NAMES: List<String> =
            listOf(
                "event",
                "release-name",
                "version-codes",
                "from-percent",
                "to-percent",
                "reason",
                "source",
                "ready-to-publish",
                "rejected",
                "summary",
                "record",
            )

        fun of(record: DecisionRecord): Map<String, String> {
            val run = Run.of(record)
            return mapOf(
                "event" to run.event,
                "release-name" to (record.release?.name ?: ""),
                "version-codes" to (record.release?.versionCodes?.joinToString(",") ?: ""),
                "from-percent" to (run.from?.let(::plain) ?: ""),
                "to-percent" to (run.to?.let(::plain) ?: ""),
                "reason" to (run.halted?.reason ?: ""),
                "source" to (run.halted?.source ?: ""),
                "ready-to-publish" to run.readyToPublish.joinToString(","),
                "rejected" to run.rejected.joinToString(","),
                "summary" to summary(record, run),
                "record" to json(record, run).toString(),
            )
        }

        /** What one run amounts to, worked out once for every layer of output. */
        private data class Run(
            val event: String,
            val from: Double?,
            val to: Double?,
            val halted: RolloutEvent.Halted?,
            val readyToPublish: List<String>,
            val rejected: List<String>,
            val error: String?,
            val wouldStep: Pair<Double, Double>?,
            val wouldHalt: Boolean,
        ) {
            companion object {
                fun of(record: DecisionRecord): Run {
                    val events = record.events
                    val halted = events.filterIsInstance<RolloutEvent.Halted>().firstOrNull()
                    val step =
                        events.firstNotNullOfOrNull {
                            when (it) {
                                is RolloutEvent.Advanced -> it.fromFraction to it.toFraction
                                is RolloutEvent.Completed -> it.fromFraction to 1.0
                                else -> null
                            }
                        }
                    val error = events.filterIsInstance<RolloutEvent.GuardError>().firstOrNull()?.message
                    val event =
                        when {
                            error != null -> "failed"
                            record.applied != true -> "none"
                            halted != null -> "halted"
                            events.any { it is RolloutEvent.Completed } -> "completed"
                            step != null -> "advanced"
                            else -> "none"
                        }
                    val stepped = event == "advanced" || event == "completed"
                    val dryRun = record.dryRun && error == null
                    return Run(
                        event = event,
                        from = step?.first?.takeIf { stepped },
                        to = step?.second?.takeIf { stepped },
                        halted = halted?.takeIf { event == "halted" },
                        readyToPublish = events.filterIsInstance<RolloutEvent.ReadyToPublish>().map { it.release.name },
                        rejected = events.filterIsInstance<RolloutEvent.Rejected>().map { it.release.name },
                        error = error,
                        wouldStep = step?.takeIf { dryRun },
                        wouldHalt = dryRun && halted != null,
                    )
                }
            }
        }

        private fun summary(
            record: DecisionRecord,
            run: Run,
        ): String {
            val app = record.app.packageName
            val release = record.release?.let { "${it.name} (${it.versionCodes.joinToString(",")})" } ?: "no release"
            val main =
                when {
                    run.event == "advanced" -> "$app $release advanced ${percent(run.from!!)} → ${percent(run.to!!)}"
                    run.event == "completed" -> "$app $release completed: ${percent(run.from!!)} → 100%"
                    run.event == "halted" -> "$app $release halted by ${run.halted!!.source}: ${run.halted.reason}"
                    run.event == "failed" -> "$app failed: ${run.error}"
                    run.wouldStep != null ->
                        "$app dry run: would move $release ${percent(run.wouldStep.first)} → " +
                            percent(run.wouldStep.second)
                    run.wouldHalt -> "$app dry run: would halt $release"
                    record.decision is Decision.NoOp -> "$app: ${(record.decision as Decision.NoOp).reason}"
                    else -> "$app: nothing done"
                }
            val waiting = run.readyToPublish.map { "$it is approved and waiting to be published" }
            val rejected = run.rejected.map { "$it was not approved" }
            return (listOf(main) + waiting + rejected).joinToString("; ")
        }

        private fun json(
            record: DecisionRecord,
            run: Run,
        ): JsonObject =
            buildJsonObject {
                put("event", run.event)
                put("release-name", record.release?.name)
                putJsonArray("version-codes") { record.release?.versionCodes?.forEach { add(it) } }
                put("from-percent", run.from?.let(::number))
                put("to-percent", run.to?.let(::number))
                put("reason", run.halted?.reason)
                put("source", run.halted?.source)
                putJsonArray("ready-to-publish") { run.readyToPublish.forEach { add(it) } }
                putJsonArray("rejected") { run.rejected.forEach { add(it) } }
                put("summary", summary(record, run))
                put("app", record.app.packageName)
                put("command", record.command)
                put("at", record.at.toString())
                put("dry-run", record.dryRun)
                put("decision", record.decision.toString())
                putJsonArray("notes") { record.notes.forEach { add(it) } }
            }

        /** `0.2` becomes `20`, as in the configuration; `0.005` becomes `0.50`. */
        private fun plain(fraction: Double): String = percent(fraction).removeSuffix("%")

        /** `0.2` becomes the JSON number `20`; `0.005` becomes `0.5`. */
        private fun number(fraction: Double): Number {
            val pct = Math.round(fraction * PERCENT_SCALE) / PERCENT_DECIMALS
            return if (pct == Math.rint(pct)) pct.toLong() else pct
        }

        private const val PERCENT_SCALE = 10_000.0
        private const val PERCENT_DECIMALS = 100.0
    }
}

class CompositeAuditLog(
    private val logs: List<AuditLog>,
) : AuditLog {
    override suspend fun record(record: DecisionRecord) = logs.forEach { it.record(record) }
}
