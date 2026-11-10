package io.github.savvasg28.rampguard.cli

import io.github.savvasg28.rampguard.core.model.Decision
import io.github.savvasg28.rampguard.core.model.DecisionRecord
import io.github.savvasg28.rampguard.core.model.RolloutEvent
import io.github.savvasg28.rampguard.core.testing.Fixtures
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test

class StepOutputsTest {
    private val live = Fixtures.release(101, "5.25.0")
    private val waiting = Fixtures.release(102, "5.26.0")

    private fun record(
        events: List<RolloutEvent>,
        applied: Boolean?,
        decision: Decision = Decision.NoOp("test"),
    ) = DecisionRecord(
        at = Fixtures.monday9,
        app = Fixtures.app,
        dryRun = applied == false,
        command = "tick",
        release = live,
        observedFraction = 0.05,
        stageIndex = 1,
        decision = decision,
        notes = emptyList(),
        events = events,
        applied = applied,
    )

    @Test
    fun `every output is always present and the names are stable`() {
        StepOutputs.of(record(emptyList(), applied = null)).keys.toList() shouldBe StepOutputs.NAMES
    }

    @Test
    fun `a step that was written reports advanced with its percentages`() {
        StepOutputs.of(record(listOf(RolloutEvent.Advanced(live, 0.05, 0.2)), applied = true)) shouldContainAll
            mapOf(
                "event" to "advanced",
                "release-name" to "5.25.0",
                "version-codes" to "101",
                "from-percent" to "5",
                "to-percent" to "20",
            )
        StepOutputs.of(record(listOf(RolloutEvent.Completed(live, 0.5)), applied = true)) shouldContainAll
            mapOf("event" to "completed", "from-percent" to "50", "to-percent" to "100")
    }

    @Test
    fun `a dry run or a failed write never reports a step`() {
        StepOutputs.of(record(listOf(RolloutEvent.Advanced(live, 0.05, 0.2)), applied = false)) shouldContainAll
            mapOf("event" to "none", "from-percent" to "", "to-percent" to "")
        val failed =
            listOf(RolloutEvent.Advanced(live, 0.05, 0.2), RolloutEvent.GuardError("write to Play failed: 409"))
        StepOutputs.of(record(failed, applied = false))["event"] shouldBe "failed"
    }

    @Test
    fun `a halt carries its reason and source`() {
        StepOutputs.of(
            record(listOf(RolloutEvent.Halted(live, "crash spike", "sentry")), applied = true),
        ) shouldContainAll
            mapOf("event" to "halted", "reason" to "crash spike", "source" to "sentry")
    }

    @Test
    fun `releases waiting for a person are reported next to whatever else happened`() {
        val events = listOf(RolloutEvent.ReadyToPublish(waiting), RolloutEvent.Advanced(live, 0.05, 0.2))
        StepOutputs.of(record(events, applied = true)) shouldContainAll
            mapOf("event" to "advanced", "ready-to-publish" to "5.26.0", "rejected" to "")
        StepOutputs.of(record(listOf(RolloutEvent.Rejected(waiting)), applied = null)) shouldContainAll
            mapOf("event" to "none", "rejected" to "5.26.0")
    }

    @Test
    fun `the summary is one line saying what the run did`() {
        fun summary(
            events: List<RolloutEvent>,
            applied: Boolean?,
            decision: Decision = Decision.NoOp("test"),
        ) = StepOutputs.of(record(events, applied, decision)).getValue("summary")

        summary(listOf(RolloutEvent.Advanced(live, 0.05, 0.2)), applied = true) shouldBe
            "com.example.app 5.25.0 (101) advanced 5% → 20%"
        summary(listOf(RolloutEvent.Completed(live, 0.5)), applied = true) shouldBe
            "com.example.app 5.25.0 (101) completed: 50% → 100%"
        summary(listOf(RolloutEvent.Halted(live, "crash spike", "sentry")), applied = true) shouldBe
            "com.example.app 5.25.0 (101) halted by sentry: crash spike"
        summary(listOf(RolloutEvent.GuardError("write to Play failed: 409")), applied = false) shouldBe
            "com.example.app failed: write to Play failed: 409"
        summary(listOf(RolloutEvent.Advanced(live, 0.05, 0.2)), applied = false) shouldBe
            "com.example.app dry run: would move 5.25.0 (101) 5% → 20%"
        summary(listOf(RolloutEvent.Halted(live, "crash spike", "sentry")), applied = false) shouldBe
            "com.example.app dry run: would halt 5.25.0 (101)"
        summary(emptyList(), applied = null, Decision.NoOp("release 5.25.0[101] is halted")) shouldBe
            "com.example.app: release 5.25.0[101] is halted"
        summary(
            listOf(RolloutEvent.Advanced(live, 0.05, 0.2), RolloutEvent.ReadyToPublish(waiting)),
            applied = true,
        ) shouldBe
            "com.example.app 5.25.0 (101) advanced 5% → 20%; 5.26.0 is approved and waiting to be published"
    }

    @Test
    fun `the record is the flat outputs as typed JSON plus the run's context`() {
        val events = listOf(RolloutEvent.Advanced(live, 0.005, 0.2), RolloutEvent.ReadyToPublish(waiting))
        val raw = StepOutputs.of(record(events, applied = true)).getValue("record")
        raw.lines().size shouldBe 1
        val json = Json.parseToJsonElement(raw).jsonObject
        json["event"]?.jsonPrimitive?.content shouldBe "advanced"
        json["release-name"]?.jsonPrimitive?.content shouldBe "5.25.0"
        json["version-codes"]?.jsonArray?.map { it.jsonPrimitive.long } shouldBe listOf(101L)
        json["from-percent"]?.jsonPrimitive?.double shouldBe 0.5
        json["to-percent"]?.jsonPrimitive?.content shouldBe "20"
        json["reason"] shouldBe JsonNull
        json["ready-to-publish"]?.jsonArray?.map { it.jsonPrimitive.content } shouldBe listOf("5.26.0")
        json["app"]?.jsonPrimitive?.content shouldBe "com.example.app"
        json["dry-run"]?.jsonPrimitive?.boolean shouldBe false
        json["at"]?.jsonPrimitive?.content shouldBe "2026-03-02T09:00:00Z"
        json.keys.toList().take(StepOutputs.NAMES.size - 1) shouldBe StepOutputs.NAMES.dropLast(1)
    }

    @Test
    fun `untrusted text stays inside its JSON string`() {
        val hostile = "x\"}\nevent=advanced"
        val outputs = StepOutputs.of(record(listOf(RolloutEvent.Halted(live, hostile, "s")), applied = true))
        Json
            .parseToJsonElement(outputs.getValue("record"))
            .jsonObject["reason"]
            ?.jsonPrimitive
            ?.content shouldBe
            hostile
    }
}
