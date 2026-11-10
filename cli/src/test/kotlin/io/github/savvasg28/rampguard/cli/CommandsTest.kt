package io.github.savvasg28.rampguard.cli

import com.github.ajalt.clikt.testing.test
import io.github.savvasg28.rampguard.core.testing.FakePlayGateway
import io.github.savvasg28.rampguard.core.testing.Fixtures
import io.github.savvasg28.rampguard.core.testing.MutableClock
import io.github.savvasg28.rampguard.core.testing.RecordingAuditLog
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class CommandsTest {
    @TempDir
    lateinit var dir: Path

    private val play = FakePlayGateway()
    private val audit = RecordingAuditLog()
    private lateinit var config: Path

    private val wiring =
        WiringFactory { _, out ->
            Wiring(
                gateway = play,
                audit =
                    CompositeAuditLog(
                        listOf(
                            StdoutAuditLog(out),
                            audit,
                            JobSummaryAuditLog(dir.resolve("summary.md")),
                            StepOutputs(dir.resolve("outputs.txt")),
                        ),
                    ),
                clock = MutableClock(Fixtures.monday9),
            )
        }

    @BeforeEach
    fun setUp() {
        config = dir.resolve("rampguard.yml").also { it.writeText(Fixtures.exampleYaml) }
        play.completedRelease(Fixtures.release(100))
        play.humanPublish(Fixtures.release(101), 0.05)
    }

    private fun run(vararg args: String) = rampguard(wiring).test(listOf(*args, "--config", config.toString()))

    @Test
    fun `validate prints the policy or every error`() {
        val ok = run("validate")
        ok.statusCode shouldBe EXIT_OK
        ok.stdout shouldContain "OK com.example.app stages=[1, 5, 20, 50, 100]"

        config.writeText("app: a\nstages: [1, 50]\nminSoakHours: 3\n")
        val bad = run("validate")
        bad.statusCode shouldBe EXIT_INVALID_CONFIG
        bad.stderr shouldContain "'minSoakHours' was removed"

        val missing = rampguard(wiring).test(listOf("validate", "--config", dir.resolve("nope.yml").toString()))
        missing.statusCode shouldBe EXIT_INVALID_CONFIG
    }

    @Test
    fun `tick steps the live release, prints the audit, writes the job summary and the step outputs`() {
        val result = run("tick")

        result.statusCode shouldBe EXIT_OK
        play.writes shouldContainExactly listOf("setRollout v101[101] 0.2")
        result.stdout shouldContain "decision: Advance(to=20%) applied=true"
        result.stdout shouldContain "event: advanced:v101[101]:5%->20%"
        dir.resolve("summary.md").readText() shouldContain "decision: Advance(to=20%) applied=true"
        val outputs = dir.resolve("outputs.txt").readText()
        outputs shouldStartWith
            "event=advanced\nrelease-name=v101\nversion-codes=101\nfrom-percent=5\nto-percent=20\n" +
            "reason=\nsource=\nready-to-publish=\nrejected=\n" +
            "summary=com.example.app v101 (101) advanced 5% → 20%\nrecord={\"event\":\"advanced\","
        outputs.lines().filter { it.isNotEmpty() }.map { it.substringBefore("=") } shouldBe StepOutputs.NAMES
    }

    @Test
    fun `tick fails the job when Play is unreadable or a write fails`() {
        play.readFailure = IllegalStateException("503")
        run("tick").statusCode shouldBe EXIT_RUN_FAILED

        play.readFailure = null
        play.writeFailure = IllegalStateException("edit conflict")
        val result = run("tick")
        result.statusCode shouldBe EXIT_RUN_FAILED
        result.stdout shouldContain "write to Play failed: edit conflict"
    }

    @Test
    fun `a dry run exits zero and writes nothing`() {
        val result = run("tick", "--dry-run")
        result.statusCode shouldBe EXIT_OK
        result.stdout shouldContain "WOULD Advance(to=20%)"
        play.writes.shouldContainExactly()
        dir.resolve("outputs.txt").readText() shouldContain "event=none\n"
    }

    @Test
    fun `halt needs a version code and sanitises free text`() {
        run("halt").statusCode shouldBe 1

        val result = run("halt", "--version-code", "101", "--reason", "crash\u0000 spike", "--source", "sentry")
        result.statusCode shouldBe EXIT_OK
        play.writes shouldContainExactly listOf("halt v101[101]")
        result.stdout shouldContain "Halt(halt requested by sentry: crash spike)"
        result.stdout shouldNotContain "\u0000"

        run("halt", "--version-code", "999").stdout shouldContain "versionCode 999 is not the active rollout"
    }

    @Test
    fun `status prints JSON and fails only when Play is unreadable`() {
        val result = run("status")
        result.statusCode shouldBe EXIT_OK
        result.stdout shouldContain "\"nextStep\": {"
        result.stdout shouldContain "\"percent\": 20"
        audit.records.shouldContainExactly()

        play.readFailure = IllegalStateException("503")
        val failed = run("status")
        failed.statusCode shouldBe EXIT_RUN_FAILED
        failed.stdout shouldContain "\"playError\": \"503\""
    }
}
