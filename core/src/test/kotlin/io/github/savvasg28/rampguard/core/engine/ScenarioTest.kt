package io.github.savvasg28.rampguard.core.engine

import io.github.savvasg28.rampguard.core.model.LifecycleState
import io.github.savvasg28.rampguard.core.testing.Fixtures
import io.github.savvasg28.rampguard.core.testing.Fixtures.release
import io.github.savvasg28.rampguard.core.testing.Simulation
import io.github.savvasg28.rampguard.core.testing.Simulation.Companion.entry
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Each scenario asserts the exact action timeline of a daily run, starting Monday 2026-03-02 09:00Z. */
class ScenarioTest {
    private val v100 = release(100)
    private val v101 = release(101)
    private val v102 = release(102)

    /** A track with v100 completed and v101 live at 1%. */
    private fun start(dryRun: Boolean = false): Simulation {
        val sim = Simulation(Fixtures.policy(), dryRun = dryRun)
        sim.play.completedRelease(v100)
        sim.play.humanPublish(v101, 0.01)
        return sim
    }

    @Test
    fun `a release live at 1 percent is stepped once per run and completed at the last stage`() =
        runTest {
            val sim = start()
            sim.run()
            sim.nextDay()
            sim.nextDay()
            sim.nextDay()
            sim.nextDay()

            sim.timeline shouldContainExactly
                listOf(
                    entry("2026-03-02T09:00:00Z", "Advance(to=5%)"),
                    entry("2026-03-03T09:00:00Z", "Advance(to=20%)"),
                    entry("2026-03-04T09:00:00Z", "Advance(to=50%)"),
                    entry("2026-03-05T09:00:00Z", "Complete"),
                    entry("2026-03-06T09:00:00Z", "NoOp(release v101[101] is completed; nothing to do)"),
                )
            sim.play.writes shouldContainExactly
                listOf(
                    "setRollout v101[101] 0.05",
                    "setRollout v101[101] 0.2",
                    "setRollout v101[101] 0.5",
                    "complete v101[101]",
                )
            sim.events shouldContainExactly
                listOf(
                    "advanced:v101[101]:1%->5%",
                    "advanced:v101[101]:5%->20%",
                    "advanced:v101[101]:20%->50%",
                    "completed:v101[101]:50%->100%",
                )
        }

    @Test
    fun `a fraction between stages is treated as the stage below it`() =
        runTest {
            val sim = start()
            sim.play.humanSetFraction(v101, 0.10)
            sim.run()
            sim.play.humanSetFraction(v101, 0.005)
            sim.nextDay()

            sim.timeline shouldContainExactly
                listOf(
                    entry("2026-03-02T09:00:00Z", "Advance(to=20%)"),
                    entry("2026-03-03T09:00:00Z", "Advance(to=1%)"),
                )
            sim.audit.records[0].notes shouldContainExactly
                listOf("fraction 10% is not a configured stage; treating it as the 5% stage")
            sim.audit.records[1].notes shouldContainExactly
                listOf("fraction 0.50% is not a configured stage; treating it as below the first stage")
        }

    @Test
    fun `a halted release is left alone whoever halted it`() =
        runTest {
            val sim = start()
            sim.run()
            sim.play.humanHalt(v101)
            sim.nextDay()
            sim.nextDay()
            sim.play.humanResume(v101)
            sim.nextDay()

            sim.timeline shouldContainExactly
                listOf(
                    entry("2026-03-02T09:00:00Z", "Advance(to=5%)"),
                    entry(
                        "2026-03-03T09:00:00Z",
                        "NoOp(release v101[101] is halted; a human must resume it in the Play Console)",
                    ),
                    entry(
                        "2026-03-04T09:00:00Z",
                        "NoOp(release v101[101] is halted; a human must resume it in the Play Console)",
                    ),
                    entry("2026-03-05T09:00:00Z", "Advance(to=20%)"),
                )
            sim.play.writes shouldContainExactly listOf("setRollout v101[101] 0.05", "setRollout v101[101] 0.2")
        }

    @Test
    fun `a human advance is simply the new starting point`() =
        runTest {
            val sim = start()
            sim.run()
            sim.play.humanSetFraction(v101, 0.5)
            sim.nextDay()

            sim.timeline shouldContainExactly
                listOf(entry("2026-03-02T09:00:00Z", "Advance(to=5%)"), entry("2026-03-03T09:00:00Z", "Complete"))
        }

    @Test
    fun `a human completion ends the rollout quietly`() =
        runTest {
            val sim = start()
            sim.run()
            sim.play.humanComplete(v101)
            sim.nextDay()
            sim.nextDay()

            sim.timeline.drop(1) shouldContainExactly
                listOf(
                    entry("2026-03-03T09:00:00Z", "NoOp(release v101[101] is completed; nothing to do)"),
                    entry("2026-03-04T09:00:00Z", "NoOp(release v101[101] is completed; nothing to do)"),
                )
            sim.events shouldContainExactly listOf("advanced:v101[101]:1%->5%")
        }

    @Test
    fun `a new release published over a staged one is stepped from where it is`() =
        runTest {
            val sim = start()
            sim.run()
            sim.play.production.removeAll { it.release.sameAs(v101) }
            sim.play.humanPublish(v102, 0.01)
            sim.nextDay()

            sim.timeline shouldContainExactly
                listOf(entry("2026-03-02T09:00:00Z", "Advance(to=5%)"), entry("2026-03-03T09:00:00Z", "Advance(to=5%)"))
            sim.play.writes.last() shouldBe "setRollout v102[102] 0.05"
        }

    @Test
    fun `halt command is applied once and ignores wrong versions and repeats`() =
        runTest {
            val sim = start()
            sim.halt(versionCode = 999)
            sim.halt(versionCode = 101, reason = "p1 \u0007alert\n\n  fired", source = "datadog")
            sim.halt(versionCode = 101)
            sim.nextDay()

            sim.timeline shouldContainExactly
                listOf(
                    entry("2026-03-02T09:00:00Z", "NoOp(versionCode 999 is not the active rollout (v101[101]))"),
                    entry("2026-03-02T09:00:00Z", "Halt(halt requested by datadog: p1 alert fired)"),
                    entry("2026-03-02T09:00:00Z", "NoOp(release v101[101] is already halted)"),
                    entry(
                        "2026-03-03T09:00:00Z",
                        "NoOp(release v101[101] is halted; a human must resume it in the Play Console)",
                    ),
                )
            sim.play.writes shouldContainExactly listOf("halt v101[101]")
            sim.events shouldContainExactly listOf("halted:v101[101]:datadog")
        }

    @Test
    fun `halt with several version codes halts when any of them is live`() =
        runTest {
            val sim = start()
            sim.halt(versionCodes = listOf(998, 999))
            sim.halt(versionCodes = listOf(100, 101), source = "workflow")

            sim.timeline shouldContainExactly
                listOf(
                    entry(
                        "2026-03-02T09:00:00Z",
                        "NoOp(none of versionCodes 998,999 is the active rollout (v101[101]))",
                    ),
                    entry("2026-03-02T09:00:00Z", "Halt(halt requested by workflow: alert)"),
                )
            sim.play.writes shouldContainExactly listOf("halt v101[101]")
        }

    @Test
    fun `halt with no active rollout is a no-op`() =
        runTest {
            val sim = Simulation(Fixtures.policy())
            sim.play.completedRelease(v100)
            sim.halt(versionCode = 100)
            sim.timeline shouldContainExactly listOf(entry("2026-03-02T09:00:00Z", "NoOp(no active rollout to halt)"))
        }

    @Test
    fun `releases waiting for a person are reported on every run`() =
        runTest {
            val sim = Simulation(Fixtures.policy())
            sim.play.completedRelease(v100)
            sim.play.humanSubmit(v101, LifecycleState.IN_REVIEW)
            sim.run()
            sim.play.production.removeAll { it.release.sameAs(v101) }
            sim.play.humanSubmit(v101, LifecycleState.APPROVED_NOT_PUBLISHED)
            sim.nextDay()
            sim.nextDay()
            sim.play.production.removeAll { it.release.sameAs(v101) }
            sim.play.humanSubmit(v101, LifecycleState.NOT_APPROVED)
            sim.nextDay()
            sim.play.humanPublish(v101, 0.01)
            sim.nextDay()

            sim.timeline.map { it.substringAfter("Z ") } shouldContainExactly
                listOf(
                    "NoOp(release v100[100] is completed; nothing to do)",
                    "NoOp(release v100[100] is completed; nothing to do)",
                    "NoOp(release v100[100] is completed; nothing to do)",
                    "NoOp(release v100[100] is completed; nothing to do)",
                    "Advance(to=5%)",
                )
            sim.events shouldContainExactly
                listOf(
                    "ready-to-publish:v101[101]",
                    "ready-to-publish:v101[101]",
                    "rejected:v101[101]",
                    "advanced:v101[101]:1%->5%",
                )
        }

    @Test
    fun `a brand new app reports what the track holds`() =
        runTest {
            val sim = Simulation(Fixtures.policy())
            sim.run()
            sim.play.humanSubmit(v101, LifecycleState.APPROVED_NOT_PUBLISHED)
            sim.nextDay()
            sim.timeline.map { it.substringAfter("Z ") } shouldContainExactly
                listOf(
                    "NoOp(no release on the production track)",
                    "NoOp(release v101[101] is approved and waiting to be published)",
                )
        }

    @Test
    fun `a dry run says what it would do and never writes`() =
        runTest {
            val sim = start(dryRun = true)
            sim.run()
            sim.nextDay()
            sim.play.humanSetFraction(v101, 0.05)
            sim.nextDay()

            sim.timeline shouldContainExactly
                listOf(
                    entry("2026-03-02T09:00:00Z", "Advance(to=5%)"),
                    entry("2026-03-03T09:00:00Z", "Advance(to=5%)"),
                    entry("2026-03-04T09:00:00Z", "Advance(to=20%)"),
                )
            sim.play.writes.shouldBeEmpty()
            sim.audit.records.map { it.applied } shouldContainExactly listOf(false, false, false)
            sim.logs.count { it.startsWith("WOULD Advance") } shouldBe 3
        }

    @Test
    fun `play unreadable does nothing and raises a guard error every time`() =
        runTest {
            val sim = start()
            sim.play.readFailure = IllegalStateException("503 from Play")
            sim.run()
            sim.nextDay()
            sim.play.readFailure = null
            sim.nextDay()

            sim.timeline.map { it.substringAfter("Z ") } shouldContainExactly
                listOf(
                    "NoOp(Play production track could not be read: 503 from Play)",
                    "NoOp(Play production track could not be read: 503 from Play)",
                    "Advance(to=5%)",
                )
            sim.events shouldContainExactly
                listOf(
                    "guard-error:Play production track could not be read: 503 from Play",
                    "guard-error:Play production track could not be read: 503 from Play",
                    "advanced:v101[101]:1%->5%",
                )
        }

    @Test
    fun `a failed write is reported and the next run tries again`() =
        runTest {
            val sim = start()
            sim.play.writeFailure = IllegalStateException("edit conflict")
            sim.run()
            sim.play.writeFailure = null
            sim.nextDay()

            sim.timeline shouldContainExactly
                listOf(entry("2026-03-02T09:00:00Z", "Advance(to=5%)"), entry("2026-03-03T09:00:00Z", "Advance(to=5%)"))
            sim.audit.records.map { it.applied } shouldContainExactly listOf(false, true)
            sim.events shouldContainExactly
                listOf(
                    "advanced:v101[101]:1%->5%",
                    "guard-error:write to Play failed: edit conflict",
                    "advanced:v101[101]:1%->5%",
                )
            sim.play.writes shouldContainExactly listOf("setRollout v101[101] 0.05")
        }
}
