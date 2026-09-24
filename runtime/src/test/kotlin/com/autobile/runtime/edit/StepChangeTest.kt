package com.autobile.runtime.edit

import com.autobile.ai.task.EditableStepAction
import com.autobile.ai.task.SkillStepEdit
import com.autobile.ai.task.SkillStepEditKind
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.ExecutionStrategy
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillStep
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.ValueRef
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Explicit step changes are applied exactly as stated or not at all. A change set that
 * half applies would leave an automation nobody asked for.
 */
class StepChangeTest {

    private val skill = SemanticSkill(
        id = "skill",
        version = 1,
        name = "Sudoku",
        goal = "Start a game",
        steps = listOf(
            step("open", ActionSpec.LaunchApp("com.example.sudoku")),
            step("easy", ActionSpec.Click),
            step("type", ActionSpec.InputText(ValueRef.Literal("hello"))),
            step("wait", ActionSpec.Wait(500)),
        ),
    )

    @Test
    fun `a step moves by the requested offset and stops at either end`() {
        val moved = applyStepChanges(skill, listOf(StepChange.Move("wait", -2)))!!
        assertThat(moved.steps.map { it.id }).containsExactly("open", "wait", "easy", "type").inOrder()

        val clamped = applyStepChanges(skill, listOf(StepChange.Move("open", -5)))!!
        assertThat(clamped.steps.map { it.id }).isEqualTo(skill.steps.map { it.id })
    }

    @Test
    fun `a step can be removed or made optional`() {
        val changed = applyStepChanges(
            skill,
            listOf(StepChange.Delete("easy"), StepChange.SetOptional("wait", true)),
        )!!

        assertThat(changed.steps.map { it.id }).containsExactly("open", "type", "wait").inOrder()
        assertThat(changed.step("wait")!!.optional).isTrue()
    }

    @Test
    fun `editing keeps what the step was recorded against`() {
        val located = skill.copy(
            steps = skill.steps.map {
                if (it.id == "type") it.copy(target = TargetSemantics("Message", locators = listOf())) else it
            },
        )
        val changed = applyStepChanges(
            located,
            listOf(
                StepChange.Update("type", description = "Type the greeting", text = "안녕하세요"),
                StepChange.Update("wait", waitMs = 2_000),
            ),
        )!!

        val typed = changed.step("type")!!
        assertThat(typed.description).isEqualTo("Type the greeting")
        assertThat((typed.action as ActionSpec.InputText).value).isEqualTo(ValueRef.Literal("안녕하세요"))
        assertThat(typed.target).isEqualTo(located.step("type")!!.target)
        assertThat(changed.step("wait")!!.action).isEqualTo(ActionSpec.Wait(2_000))
    }

    @Test
    fun `a field that does not belong to the step rejects the change`() {
        assertThat(applyStepChanges(skill, listOf(StepChange.Update("easy", text = "nope")))).isNull()
        assertThat(applyStepChanges(skill, listOf(StepChange.Update("easy", waitMs = 100)))).isNull()
    }

    @Test
    fun `one unknown step rejects the whole set`() {
        val result = applyStepChanges(skill, listOf(StepChange.Delete("easy"), StepChange.Delete("missing")))
        assertThat(result).isNull()
    }

    @Test
    fun `inserting uses the builder and lands next to its anchor`() {
        val edit = SkillStepEdit(SkillStepEditKind.INSERT_AFTER, "", EditableStepAction.BACK)
        val inserted = step("back", ActionSpec.Back)

        val changed = applyStepChanges(skill, listOf(StepChange.Insert("easy", after = true, edit = edit))) { inserted }!!

        assertThat(changed.steps.map { it.id }).containsExactly("open", "easy", "back", "type", "wait").inOrder()
    }

    @Test
    fun `without a builder an insert is rejected rather than guessed`() {
        val edit = SkillStepEdit(SkillStepEditKind.INSERT_AFTER, "", EditableStepAction.BACK)
        assertThat(applyStepChanges(skill, listOf(StepChange.Insert(null, after = true, edit = edit)))).isNull()
    }

    @Test
    fun `replacing every step also sets how they run`() {
        val changed = applyStepChanges(
            skill.copy(strategy = ExecutionStrategy.AGENT_FIRST),
            listOf(StepChange.ReplaceAll(listOf(step("learned", ActionSpec.Click)), ExecutionStrategy.STEPS_FIRST)),
        )!!

        assertThat(changed.steps.map { it.id }).containsExactly("learned")
        assertThat(changed.strategy).isEqualTo(ExecutionStrategy.STEPS_FIRST)
    }

    @Test
    fun `nothing may leave an automation without steps or with two steps sharing an id`() {
        assertThat(applyStepChanges(skill, skill.steps.map { StepChange.Delete(it.id) })).isNull()
        assertThat(
            applyStepChanges(skill, listOf(StepChange.InsertSteps("easy", after = true, steps = listOf(step("easy", ActionSpec.Back))))),
        ).isNull()
    }

    @Test
    fun `an added launch step makes its app a requirement`() {
        val changed = applyStepChanges(
            skill,
            listOf(StepChange.InsertSteps("wait", after = true, steps = listOf(step("chat", ActionSpec.LaunchApp("com.example.chat"))))),
        )!!

        assertThat(changed.runtimeRequirements.requiredPackages).containsExactly("com.example.sudoku", "com.example.chat")
    }

    private fun step(id: String, action: ActionSpec) = SkillStep(
        id = id,
        intent = StepIntent.SELECT_ITEM,
        target = TargetSemantics(id),
        action = action,
        description = id,
    )
}
