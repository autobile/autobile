package com.autobile.runtime.agent

import com.autobile.ai.task.CommandIntent
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.ExecutionStrategy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DirectCommandCompilationTest {
    @Test
    fun `a command without a saved skill becomes a goal the agent drives`() {
        val skill = buildAdHocAgentSkill(
            intent(),
            packageName = "com.example.puzzle",
            command = "풀던 퍼즐 끝내줘",
            now = 10L,
        )

        assertThat(skill.steps).isEmpty()
        assertThat(skill.strategy).isEqualTo(ExecutionStrategy.AGENT_FIRST)
        assertThat(skill.postconditions.single().description).contains("solved board")
        assertThat(skill.runtimeRequirements.requiredPackages).containsExactly("com.example.puzzle")
        assertThat(skill.guidance).containsExactly("풀던 퍼즐 끝내줘")
        assertThat(skill.autonomyLevel).isEqualTo(AutonomyLevel.L2_ASK_BEFORE_ACTION)
    }

    @Test
    fun `a command whose app is not open yet still runs and names the app to open`() {
        val skill = buildAdHocAgentSkill(intent(), packageName = null, command = "Solve the puzzle", now = 10L)

        assertThat(skill.runtimeRequirements.requiredPackages).isEmpty()
        assertThat(skill.description).isEqualTo("Use the Puzzle app.")
        assertThat(skill.runtimeRequirements.requiresScreenshot).isFalse()
    }

    private fun intent() = CommandIntent(
        goal = "Solve the visible puzzle",
        appHint = "Puzzle",
        parameters = emptyMap(),
        referencesCurrentScreen = true,
        confidence = 0.9f,
        completionCriteria = "A solved board or success screen is visible",
    )
}
