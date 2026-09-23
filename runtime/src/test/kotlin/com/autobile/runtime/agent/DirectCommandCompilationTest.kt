package com.autobile.runtime.agent

import com.autobile.ai.task.CommandIntent
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.AutonomyLevel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DirectCommandCompilationTest {
    @Test
    fun `a command without a saved skill becomes a bounded visual task`() {
        val skill = buildAdHocVisualSkill(
            CommandIntent(
                goal = "Solve the visible puzzle",
                appHint = "Puzzle",
                parameters = emptyMap(),
                referencesCurrentScreen = true,
                confidence = 0.9f,
                completionCriteria = "A solved board or success screen is visible",
            ),
            packageName = "com.example.puzzle",
            now = 10L,
        )

        val action = skill.steps.single().action as ActionSpec.VisualTask
        assertThat(action.objective).isEqualTo("Solve the visible puzzle")
        assertThat(action.completionCriteria).contains("solved board")
        assertThat(skill.runtimeRequirements.requiresScreenshot).isTrue()
        assertThat(skill.runtimeRequirements.requiredPackages).containsExactly("com.example.puzzle")
        assertThat(skill.autonomyLevel).isEqualTo(AutonomyLevel.L2_ASK_BEFORE_ACTION)
    }
}
