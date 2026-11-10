package io.github.savvasg28.rampguard.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import io.github.savvasg28.rampguard.core.model.HaltRequest
import io.github.savvasg28.rampguard.core.model.RolloutEvent
import io.github.savvasg28.rampguard.core.policy.Policy
import io.github.savvasg28.rampguard.core.policy.PolicyParser
import io.github.savvasg28.rampguard.core.policy.PolicyResult
import io.github.savvasg28.rampguard.core.runner.RunOutcome
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.readText

/** Default location of the policy inside the app repository. */
const val DEFAULT_CONFIG_PATH: String = ".github/rampguard.yml"

/** Exit codes. A run that could not read Play or could not write what it decided fails the job. */
const val EXIT_OK: Int = 0
const val EXIT_RUN_FAILED: Int = 1
const val EXIT_INVALID_CONFIG: Int = 2

class Rampguard : CliktCommand(name = "rampguard") {
    override fun help(context: Context): String =
        "Steps Google Play staged rollouts on a schedule; halts them on command."

    override fun run() = Unit
}

/** Shared `--config` option, policy loading and adapter wiring for every subcommand. */
abstract class PolicyCommand(
    name: String,
    private val wiring: WiringFactory,
) : CliktCommand(name = name) {
    private val configPath: Path by option("--config", "-c", help = "Path to rampguard.yml")
        .path(mustExist = false)
        .default(Path.of(DEFAULT_CONFIG_PATH))

    protected fun loadPolicy(): Policy {
        val text =
            try {
                configPath.readText()
            } catch (e: java.io.IOException) {
                echo("error: cannot read config $configPath: ${e.message}", err = true)
                throw ProgramResult(EXIT_INVALID_CONFIG)
            }
        return when (val result = PolicyParser.parse(text)) {
            is PolicyResult.Valid -> result.policy
            is PolicyResult.Invalid -> {
                echo("error: invalid configuration $configPath", err = true)
                echo(result.render(), err = true)
                throw ProgramResult(EXIT_INVALID_CONFIG)
            }
        }
    }

    protected fun wire(policy: Policy): Wiring =
        try {
            wiring.create(policy) { echo(it) }
        } catch (e: CredentialsMissingException) {
            echo("error: ${e.message}", err = true)
            throw ProgramResult(EXIT_RUN_FAILED)
        }

    /** A run fails the job when Play could not be read or a decided write did not happen. */
    protected fun exit(outcome: RunOutcome): Nothing {
        val failed = outcome.record.events.any { it is RolloutEvent.GuardError }
        throw ProgramResult(if (failed) EXIT_RUN_FAILED else EXIT_OK)
    }
}

class Validate(
    wiring: WiringFactory,
) : PolicyCommand("validate", wiring) {
    override fun help(context: Context): String = "Check rampguard.yml and print the effective policy."

    override fun run() {
        val policy = loadPolicy()
        echo("OK ${policy.app} stages=${policy.stages}")
    }
}

class Tick(
    wiring: WiringFactory,
) : PolicyCommand("tick", wiring) {
    override fun help(context: Context): String =
        "Read the production track and step the live release to its next stage."

    private val dryRun: Boolean by option("--dry-run", help = "Decide and report, but write nothing to Play").flag()

    override fun run() {
        val policy = loadPolicy()
        val outcome = runBlocking { wire(policy).runner(policy, dryRun) { echo(it) }.tick() }
        exit(outcome)
    }
}

class Halt(
    wiring: WiringFactory,
) : PolicyCommand("halt", wiring) {
    override fun help(context: Context): String =
        "Halt the live release in Play if one of its version codes matches. Idempotent."

    private val versionCodes: List<Long> by option(
        "--version-code",
        help = "versionCode of the rollout to halt; several, comma-separated, halt if any of them is live",
    ).long().split(",").required()
    private val reason: String by option(
        "--reason",
        help = "Why; recorded in the audit and the outputs",
    ).default("manual")
    private val source: String by option("--source", help = "Who or what asked").default("manual")
    private val dryRun: Boolean by option("--dry-run", help = "Decide and report, but write nothing to Play").flag()

    override fun run() {
        val policy = loadPolicy()
        val request = HaltRequest(versionCodes, reason, source)
        val outcome = runBlocking { wire(policy).runner(policy, dryRun) { echo(it) }.halt(request) }
        exit(outcome)
    }
}

class Status(
    wiring: WiringFactory,
) : PolicyCommand("status", wiring) {
    override fun help(context: Context): String = "Print the production track and the next step as JSON. Read-only."

    override fun run() {
        val policy = loadPolicy()
        val status = runBlocking { wire(policy).runner(policy, dryRun = true) {}.status() }
        echo(status.toJson())
        if (status.playError != null) throw ProgramResult(EXIT_RUN_FAILED)
    }
}

fun rampguard(wiring: WiringFactory = DefaultWiring): Rampguard =
    Rampguard().subcommands(Validate(wiring), Tick(wiring), Halt(wiring), Status(wiring))

fun main(args: Array<String>) = rampguard().main(args)
