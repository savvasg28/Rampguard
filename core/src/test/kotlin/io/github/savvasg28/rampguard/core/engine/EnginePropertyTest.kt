package io.github.savvasg28.rampguard.core.engine

import io.github.savvasg28.rampguard.core.model.Decision
import io.github.savvasg28.rampguard.core.model.HaltRequest
import io.github.savvasg28.rampguard.core.model.LifecycleState
import io.github.savvasg28.rampguard.core.model.Observation
import io.github.savvasg28.rampguard.core.model.ReleaseId
import io.github.savvasg28.rampguard.core.model.ReleaseSnapshot
import io.github.savvasg28.rampguard.core.model.RolloutStatus
import io.github.savvasg28.rampguard.core.policy.Policy
import io.github.savvasg28.rampguard.core.testing.Fixtures
import io.github.savvasg28.rampguard.core.testing.Simulation
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeMonotonicallyIncreasing
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** 1,000 generated cases each. */
class EnginePropertyTest {
    private val iterations = 1_000
    private val release = ReleaseId(listOf(101L), "v101")
    private val previous = ReleaseId(listOf(100L), "v100")

    private val arbPolicy: Arb<Policy> =
        Arb.set(Arb.int(1..99), 1..5).map { Fixtures.policy(stages = it.sorted() + 100) }

    private fun arbObservation(policy: Policy): Arb<Observation> =
        Arb.bind(
            Arb.choice(Arb.element(policy.stageFractions.dropLast(1)), Arb.numericDouble(0.0001, 0.9999)),
            Arb.element(RolloutStatus.IN_PROGRESS, RolloutStatus.IN_PROGRESS, RolloutStatus.HALTED),
            Arb.boolean(),
        ) { fraction, status, withPrevious ->
            Observation(
                buildList {
                    if (withPrevious) {
                        add(
                            ReleaseSnapshot(previous, LifecycleState.PUBLISHED, RolloutStatus.COMPLETED, null),
                        )
                    }
                    add(ReleaseSnapshot(release, LifecycleState.PUBLISHED, status, fraction))
                },
            )
        }

    private suspend fun forAllRuns(block: (Policy, Observation, Verdict) -> Unit) {
        checkAll(iterations, arbPolicy) { policy ->
            checkAll(1, arbObservation(policy)) { obs -> block(policy, obs, Engine.decide(policy, obs)) }
        }
    }

    @Test
    fun `an in-progress release always moves to exactly the next configured stage above its fraction`() =
        runTest {
            forAllRuns { policy, obs, verdict ->
                val active = obs.activeRollout!!
                val fraction = active.userFraction!!
                if (active.status != RolloutStatus.IN_PROGRESS) {
                    verdict.decision.isWrite.shouldBeFalse()
                    return@forAllRuns
                }
                val above = policy.stageFractions.filter { it > fraction + 1e-9 }
                when (val d = verdict.decision) {
                    is Decision.Advance -> {
                        d.toFraction shouldBe above.first()
                        (d.toFraction > fraction).shouldBeTrue()
                        d.toStageIndex shouldBe policy.stageAtOrBelow(fraction) + 1
                    }
                    is Decision.Complete -> (above.size <= 1).shouldBeTrue()
                    else -> error("an in-progress release must be advanced or completed, got $d")
                }
            }
        }

    @Test
    fun `deciding is deterministic`() =
        runTest {
            forAllRuns { policy, obs, verdict -> Engine.decide(policy, obs) shouldBe verdict }
        }

    @Test
    fun `halt only halts the release whose version code was asked for, and only once`() =
        runTest {
            checkAll(iterations, arbPolicy, Arb.list(Arb.long(90L..110L), 1..3)) { policy, codes ->
                checkAll(1, arbObservation(policy)) { obs ->
                    val verdict = Engine.decideHalt(policy, obs, HaltRequest(codes, "r", "s"))
                    val active = obs.activeRollout!!
                    val shouldHalt =
                        codes.any(active.release::contains) && active.status == RolloutStatus.IN_PROGRESS
                    (verdict.decision is Decision.Halt) shouldBe shouldHalt
                    verdict.events.size shouldBe (if (shouldHalt) 1 else 0)
                }
            }
        }

    @Test
    fun `fractions written over a sequence of runs are strictly increasing and land on stages`() =
        runTest {
            checkAll(iterations, arbPolicy, Arb.list(Arb.boolean(), 3..12)) { policy, haltsAt ->
                val sim = Simulation(policy)
                sim.play.humanPublish(release, policy.fractionOf(0))
                for (halt in haltsAt) {
                    if (halt) sim.halt(101) else sim.run()
                    sim.clock.advanceHours(24)
                }
                val fractions =
                    sim.play.writes
                        .filter { it.startsWith("setRollout") }
                        .map { it.substringAfterLast(' ').toDouble() }
                fractions.shouldBeMonotonicallyIncreasing()
                fractions.toSet().size shouldBe fractions.size
                fractions.forEach { policy.isOnStage(it).shouldBeTrue() }
                (sim.play.writes.count { it.startsWith("halt") } <= 1).shouldBeTrue()
            }
        }
}
