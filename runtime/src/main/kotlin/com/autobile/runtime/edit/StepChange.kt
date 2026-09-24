package com.autobile.runtime.edit

import com.autobile.ai.task.SkillStepEdit
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.ExecutionStrategy
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillStep
import com.autobile.core.model.ValueRef

/**
 * One explicit change to an automation's steps.
 *
 * These are applied exactly as stated, with no model in between: the person moving a
 * step up, or a run recording the route it actually took, already knows what the result
 * should be. Interpreting a sentence is [SkillEditor.preview]'s job; this is the direct
 * path, and it is what makes an edit instant, predictable and free.
 */
sealed interface StepChange {
    /** Whether the change alters what the automation does, rather than how it reads. */
    val changesMeaning: Boolean

    data class Move(val stepId: String, val offset: Int) : StepChange {
        override val changesMeaning: Boolean get() = true
    }

    data class Delete(val stepId: String) : StepChange {
        override val changesMeaning: Boolean get() = true
    }

    /** An optional step is skipped when its target is not there, instead of stopping the run. */
    data class SetOptional(val stepId: String, val optional: Boolean) : StepChange {
        override val changesMeaning: Boolean get() = false
    }

    /**
     * Edits a step in place, keeping everything recorded about how to find its target.
     *
     * Null leaves a field as it is. [text] applies to a typing step and [waitMs] to a
     * wait; asking for either on another kind of step is rejected rather than ignored.
     */
    data class Update(
        val stepId: String,
        val description: String? = null,
        val text: String? = null,
        val waitMs: Long? = null,
    ) : StepChange {
        override val changesMeaning: Boolean get() = text != null
    }

    /**
     * Adds a new step described by [edit], next to [anchorStepId], or at the end when
     * there is no anchor.
     */
    data class Insert(
        val anchorStepId: String?,
        val after: Boolean,
        val edit: SkillStepEdit,
    ) : StepChange {
        override val changesMeaning: Boolean get() = true
    }

    /** Adds already-built steps, such as ones learned from a run, next to an anchor. */
    data class InsertSteps(
        val anchorStepId: String,
        val after: Boolean,
        val steps: List<SkillStep>,
    ) : StepChange {
        override val changesMeaning: Boolean get() = true
    }

    /** Replaces one step with already-built steps. */
    data class ReplaceStep(val stepId: String, val steps: List<SkillStep>) : StepChange {
        override val changesMeaning: Boolean get() = true
    }

    /** Replaces every step, and how they run, with a route that is known to work. */
    data class ReplaceAll(
        val steps: List<SkillStep>,
        val strategy: ExecutionStrategy,
    ) : StepChange {
        override val changesMeaning: Boolean get() = true
    }
}

/**
 * Applies [changes] in order, or returns null when any of them cannot be applied as stated.
 *
 * Nothing is partially applied: one change naming a step that no longer exists rejects
 * the whole set, because the rest were chosen assuming it did.
 *
 * @param buildInserted builds the step a [StepChange.Insert] describes. Callers without a
 *   builder reject inserts rather than guess at one.
 */
fun applyStepChanges(
    skill: SemanticSkill,
    changes: List<StepChange>,
    buildInserted: (SkillStepEdit) -> SkillStep? = { null },
): SemanticSkill? {
    val steps = skill.steps.toMutableList()
    var strategy = skill.strategy
    for (change in changes) {
        fun indexOf(id: String) = steps.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        when (change) {
            is StepChange.Move -> {
                val from = indexOf(change.stepId) ?: return null
                val to = (from + change.offset).coerceIn(0, steps.lastIndex)
                steps.add(to, steps.removeAt(from))
            }
            is StepChange.Delete -> steps.removeAt(indexOf(change.stepId) ?: return null)
            is StepChange.SetOptional -> {
                val at = indexOf(change.stepId) ?: return null
                steps[at] = steps[at].copy(optional = change.optional)
            }
            is StepChange.Update -> {
                val at = indexOf(change.stepId) ?: return null
                steps[at] = updateStep(steps[at], change) ?: return null
            }
            is StepChange.Insert -> {
                val built = buildInserted(change.edit) ?: return null
                val anchor = change.anchorStepId
                val at = if (anchor == null) {
                    steps.size
                } else {
                    (indexOf(anchor) ?: return null) + if (change.after) 1 else 0
                }
                steps.add(at, built)
            }
            is StepChange.InsertSteps -> {
                if (change.steps.isEmpty()) return null
                val at = (indexOf(change.anchorStepId) ?: return null) + if (change.after) 1 else 0
                steps.addAll(at, change.steps)
            }
            is StepChange.ReplaceStep -> {
                if (change.steps.isEmpty()) return null
                val at = indexOf(change.stepId) ?: return null
                steps.removeAt(at)
                steps.addAll(at, change.steps)
            }
            is StepChange.ReplaceAll -> {
                if (change.steps.isEmpty()) return null
                steps.clear()
                steps.addAll(change.steps)
                strategy = change.strategy
            }
        }
    }
    // Ids are how every later edit, repair and history record finds a step. Two
    // steps sharing one would make those silently apply to the wrong step.
    if (steps.isEmpty() || steps.map { it.id }.toSet().size != steps.size) return null
    val needsVision = steps.any { it.action is ActionSpec.VisualTask }
    return skill.copy(
        steps = steps,
        strategy = strategy,
        runtimeRequirements = skill.runtimeRequirements.copy(
            requiresScreenshot = skill.runtimeRequirements.requiresScreenshot || needsVision,
            requiredPackages = (
                skill.runtimeRequirements.requiredPackages +
                    steps.mapNotNull { (it.action as? ActionSpec.LaunchApp)?.packageName }
                ).distinct(),
        ),
    )
}

private fun updateStep(step: SkillStep, change: StepChange.Update): SkillStep? {
    var updated = step
    change.description?.trim()?.let { description ->
        if (description.isEmpty()) return null
        updated = updated.copy(description = description)
    }
    change.text?.let { text ->
        val input = updated.action as? ActionSpec.InputText ?: return null
        if (text.isEmpty()) return null
        updated = updated.copy(action = input.copy(value = ValueRef.Literal(text)))
    }
    change.waitMs?.let { millis ->
        if (updated.action !is ActionSpec.Wait) return null
        updated = updated.copy(action = ActionSpec.Wait(millis.coerceIn(MIN_STEP_WAIT_MS, MAX_STEP_WAIT_MS)))
    }
    return updated
}

private const val MIN_STEP_WAIT_MS = 100L
private const val MAX_STEP_WAIT_MS = 30_000L
