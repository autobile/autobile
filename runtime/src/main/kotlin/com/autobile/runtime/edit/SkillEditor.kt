package com.autobile.runtime.edit

import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.task.AiTasks
import com.autobile.ai.task.BehaviorEditMode
import com.autobile.ai.task.SkillEdit
import com.autobile.ai.task.SkillEditField
import com.autobile.core.common.TemporalText
import com.autobile.core.common.TimeSource
import com.autobile.core.data.SkillStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.ConditionKind
import com.autobile.core.model.ExpectedState
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.PatchAuthor
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.ResolverKind
import com.autobile.core.model.SkillConstant
import com.autobile.core.model.SkillVersionRecord
import com.autobile.core.model.SkillVariable
import com.autobile.core.model.SkillStep
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TriggerSpec
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValueRef
import com.autobile.core.model.ValueType
import com.autobile.core.model.VariableBinding
import com.autobile.runtime.trigger.TriggerScheduler
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Applies a bounded, plain-language change to a saved automation. */
class SkillEditor(
    private val router: AiRuntimeRouter,
    private val skillStore: SkillStore,
    private val scheduler: TriggerScheduler,
    private val time: TimeSource = TimeSource.System,
) {
    suspend fun preview(skillId: String, request: String, localOnly: Boolean): SkillEditPreview {
        val skill = skillStore.get(skillId) ?: return SkillEditPreview.Rejected("Automation not found")
        return preview(skill, request, localOnly)
    }

    /**
     * Previews a change against a skill the caller already holds.
     *
     * Used while reviewing a freshly compiled demonstration, before it has an entry in
     * the store: a misreading of what the user meant is easiest to catch at that point,
     * and hardest to notice once the automation has been running for a week.
     */
    suspend fun preview(skill: SemanticSkill, request: String, localOnly: Boolean): SkillEditPreview {
        if (request.isBlank()) return SkillEditPreview.Rejected("Describe the change you want")

        parseDeterministic(request)?.let { return buildPreview(skill, it) }

        val routed = router.infer(
            label = "skill-edit",
            schema = AiTasks.skillEdit,
            prompt = AiTasks.skillEditPrompt(skill.editableSummary(), request.trim()),
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            requirements = InferenceRequirements(
                minConfidence = MIN_EDIT_CONFIDENCE,
                localOnly = localOnly,
            ),
        )
        val edit = routed.value ?: return SkillEditPreview.Rejected(
            "The change could not be understood safely",
        )
        return buildPreview(skill, edit)
    }

    /** Handles obvious time changes without spending battery or exposing text to a model. */
    private fun parseDeterministic(request: String): SkillEdit? {
        if (hasCurrentMomentIntent(request)) {
            return SkillEdit(
                field = SkillEditField.INPUT_TEXT,
                newValue = request.trim(),
                meaningChanged = false,
                summary = "Use the current date and time when the automation runs",
                confidence = 1f,
            )
        }
        val match = TIME_PATTERN.findAll(request).lastOrNull()
            ?: KOREAN_TIME_PATTERN.findAll(request).lastOrNull()
            ?: return null
        return SkillEdit(
            field = SkillEditField.TRIGGER_TIME,
            newValue = match.value.trim(),
            meaningChanged = false,
            summary = "Change the schedule to ${match.value.trim()}",
            confidence = 1f,
        )
    }

    suspend fun apply(preview: SkillEditPreview.Ready): SkillEditApplyResult {
        val current = skillStore.get(preview.original.id)
            ?: return SkillEditApplyResult.Rejected("Automation no longer exists")
        if (current.version != preview.original.version) {
            return SkillEditApplyResult.Rejected("Automation changed while this edit was being reviewed")
        }

        val record = SkillVersionRecord(
            version = preview.updated.version,
            createdAt = time.nowMillis(),
            author = PatchAuthor.USER,
            reason = preview.summary,
            summary = preview.summary,
            changedStepIds = preview.changedStepIds,
        )
        val saved = skillStore.save(preview.updated.copy(history = current.history), record)
        scheduler.cancel(saved.id)
        scheduler.schedule(saved)
        return SkillEditApplyResult.Applied(saved)
    }

    internal fun buildPreview(skill: SemanticSkill, edit: SkillEdit): SkillEditPreview {
        val value = edit.newValue.trim()
        if (value.isEmpty()) return SkillEditPreview.Rejected("The replacement value is empty")

        val patched = when (edit.field) {
            SkillEditField.TRIGGER_TIME -> patchTime(skill, value)
            SkillEditField.TRIGGER_NOTIFICATION -> skill.copy(
                trigger = TriggerSpec.Notification(semanticCondition = value),
            )
            SkillEditField.DESTINATION -> patchDestination(skill, value)
            SkillEditField.VALUE_FIELD -> patchValueField(skill, value)
            SkillEditField.INPUT_TEXT -> patchCurrentMoment(skill, value)
            SkillEditField.NAME -> skill.copy(name = value.take(MAX_NAME_LENGTH))
            SkillEditField.BEHAVIOR -> patchBehavior(skill, edit)
            SkillEditField.UNKNOWN -> null
        } ?: return SkillEditPreview.Rejected("That kind of change is not supported yet")

        if (patched == skill) return SkillEditPreview.Rejected("The requested change did not alter this automation")

        val beforeById = skill.steps.associateBy { it.id }
        val afterById = patched.steps.associateBy { it.id }
        val changedSteps = (beforeById.keys + afterById.keys)
            .filter { beforeById[it] != afterById[it] }
        val summary = edit.summary.ifBlank { "Update ${edit.field.name.lowercase().replace('_', ' ')} to $value" }
        return SkillEditPreview.Ready(
            original = skill,
            updated = patched.copy(version = skill.version + 1),
            summary = summary,
            meaningChanged = edit.meaningChanged,
            changedStepIds = changedSteps,
        )
    }

    private fun patchTime(skill: SemanticSkill, value: String): SemanticSkill? {
        val match = TIME_PATTERN.find(value) ?: KOREAN_TIME_PATTERN.find(value) ?: return null
        val hour = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val minute = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        val days = (skill.trigger as? TriggerSpec.Time)?.daysOfWeek.orEmpty()
        return skill.copy(trigger = TriggerSpec.Time(hour, minute, days))
    }

    private fun patchDestination(skill: SemanticSkill, value: String): SemanticSkill {
        val destinationIndex = skill.constants.indexOfFirst { constant ->
            constant.semanticRole.equals("destination", ignoreCase = true) ||
                DESTINATION_NAMES.any { constant.name.contains(it, ignoreCase = true) }
        }.takeIf { it >= 0 }

        val constants = if (destinationIndex == null) {
            skill.constants + SkillConstant(
                name = "destination",
                value = value,
                description = "Where the result is sent",
                semanticRole = "destination",
            )
        } else {
            skill.constants.mapIndexed { index, constant ->
                if (index == destinationIndex) constant.copy(value = value, semanticRole = "destination") else constant
            }
        }
        val steps = skill.steps.map { step ->
            if (step.intent != StepIntent.SEND && step.intent != StepIntent.SHARE) return@map step
            step.copy(
                target = step.target.copy(
                    intentLabel = value,
                    description = "Send to $value",
                    locators = step.target.locators.filterNot {
                        it.kind == LocatorKind.TEXT || it.kind == LocatorKind.CONTENT_DESCRIPTION
                    },
                ),
                description = "Send to $value",
            )
        }
        return skill.copy(constants = constants, steps = steps)
    }

    private fun patchValueField(skill: SemanticSkill, value: String): SemanticSkill {
        val steps = skill.steps.map { step ->
            if (step.intent != StepIntent.READ_VALUE) return@map step
            step.copy(
                target = step.target.copy(
                    intentLabel = value,
                    description = value,
                    valueSemantics = step.target.valueSemantics?.copy(fieldName = value),
                    locators = step.target.locators.filterNot {
                        it.kind == LocatorKind.TEXT || it.kind == LocatorKind.CONTENT_DESCRIPTION
                    },
                ),
                validation = step.validation.copy(
                    valueConstraints = step.validation.valueConstraints?.copy(fieldName = value),
                ),
                description = "Read $value",
            )
        }
        return skill.copy(steps = steps)
    }

    /**
     * Applies the bounded behaviour correction that is safe to derive without inventing
     * new gameplay. A request to keep playing / not leave removes recorded exit moves
     * and adds an observable package post-condition. Other free-form behaviour changes
     * fail closed instead of displaying an "applied" message for a cosmetic goal edit.
     */
    private fun patchBehavior(skill: SemanticSkill, edit: SkillEdit): SemanticSkill? {
        return when (edit.behaviorMode) {
            BehaviorEditMode.VISUAL_UNTIL_COMPLETE -> patchCompletionBeforeExit(skill, edit)
            BehaviorEditMode.STAY_IN_APP -> patchStayInApp(skill, edit)
            BehaviorEditMode.UNSUPPORTED -> null
        }
    }

    /**
     * Keeps the demonstrated exit, but makes it unreachable until a bounded visual
     * gameplay loop has positively identified the game's completed state.
     *
     * Cached locators are deliberately cleared from the loop step: repeatedly clicking
     * the taught "start" control cannot finish a game whose next correct action changes
     * from frame to frame. Each attempt must instead be grounded from fresh pixels.
     */
    private fun patchCompletionBeforeExit(skill: SemanticSkill, edit: SkillEdit): SemanticSkill? {
        val exitIndex = selectedExitIndexes(skill, edit).lastOrNull() ?: return null
        if (exitIndex <= 0) return null
        val playIndex = (exitIndex - 1 downTo 0).firstOrNull { index ->
            val step = skill.steps[index]
            step.action !is ActionSpec.LaunchApp && !isExitStep(step) && isVisualGameplayAction(step.action)
        } ?: return null
        val appPackage = gamePackage(skill, exitIndex) ?: return null

        val objective = edit.objective.ifBlank { edit.newValue }.trim()
        val completionCriteria = edit.completionCriteria.trim().takeIf { it.isNotBlank() } ?: return null
        val steps = skill.steps.mapIndexed { index, step ->
            when (index) {
                playIndex -> step.copy(
                    target = step.target.copy(
                        intentLabel = "Next correct action to complete the game",
                        description = "Choose the next in-game action that advances toward a fully completed game",
                        synonyms = listOf("next move", "continue playing", "finish game", "complete level"),
                        locators = emptyList(),
                    ),
                    action = ActionSpec.VisualTask(
                        objective = objective,
                        completionCriteria = completionCriteria,
                        maxActions = MAX_GAMEPLAY_ACTIONS,
                    ),
                    preferredResolver = ResolverKind.VISION,
                    expectedState = ExpectedState(
                        requiredPackage = appPackage,
                        description = "The game is fully completed and no required gameplay remains",
                    ),
                    validation = step.validation.copy(
                        mode = ValidationMode.SEMANTIC,
                        timeoutMs = maxOf(step.validation.timeoutMs, GAMEPLAY_SETTLE_TIMEOUT_MS),
                        expectation = completionCriteria,
                        goalCritical = true,
                    ),
                    fallback = step.fallback.copy(
                        allowVision = true,
                        maxRetries = maxOf(step.fallback.maxRetries, MAX_GAMEPLAY_ACTIONS),
                    ),
                    description = "Play until the game is fully completed",
                )
                exitIndex -> step.copy(description = "Exit only after the game completion check passes")
                else -> step
            }
        }
        val staleStayPostconditions = skill.postconditions.filterNot { condition ->
            condition.kind == ConditionKind.STRUCTURAL && condition.packageName == appPackage &&
                STAY_POSTCONDITION_TERMS.any(condition.description.lowercase()::contains)
        }
        return skill.copy(
            goal = edit.newValue,
            steps = steps,
            postconditions = staleStayPostconditions,
            runtimeRequirements = skill.runtimeRequirements.copy(requiresScreenshot = true),
        )
    }

    private fun patchStayInApp(skill: SemanticSkill, edit: SkillEdit): SemanticSkill? {
        val exitIndexes = selectedExitIndexes(skill, edit).toSet()
        val removed = skill.steps.filterIndexed { index, _ -> index in exitIndexes }
        if (removed.isEmpty()) return null
        val retained = skill.steps.filterIndexed { index, _ -> index !in exitIndexes }
        if (retained.isEmpty()) return null

        val appPackage = retained.asSequence().mapNotNull { step ->
            (step.action as? ActionSpec.LaunchApp)?.packageName
                ?: step.target.screen?.packageName
                ?: step.target.locators.firstNotNullOfOrNull { it.packageName }
        }.firstOrNull() ?: skill.runtimeRequirements.requiredPackages.singleOrNull()

        val postconditions = if (appPackage == null || skill.postconditions.any { it.packageName == appPackage }) {
            skill.postconditions
        } else {
            skill.postconditions + com.autobile.core.model.Condition(
                description = "The game remains in the foreground",
                kind = com.autobile.core.model.ConditionKind.STRUCTURAL,
                packageName = appPackage,
            )
        }
        return skill.copy(goal = edit.newValue, steps = retained, postconditions = postconditions)
    }

    private fun selectedExitIndexes(skill: SemanticSkill, edit: SkillEdit): List<Int> {
        val selectedIds = edit.exitStepIds.toSet()
        if (selectedIds.isNotEmpty()) {
            return skill.steps.mapIndexedNotNull { index, step -> index.takeIf { step.id in selectedIds } }
        }
        return skill.steps.mapIndexedNotNull { index, step -> index.takeIf { isExitStep(step) } }
    }

    private fun gamePackage(skill: SemanticSkill, beforeIndex: Int): String? =
        skill.steps.take(beforeIndex).asSequence().mapNotNull { step ->
            (step.action as? ActionSpec.LaunchApp)?.packageName
                ?: step.target.screen?.packageName
                ?: step.target.locators.firstNotNullOfOrNull { it.packageName }
        }.firstOrNull { it.isNotBlank() } ?: skill.runtimeRequirements.requiredPackages.singleOrNull()

    private fun isVisualGameplayAction(action: ActionSpec): Boolean = when (action) {
        ActionSpec.Click, is ActionSpec.Tap, is ActionSpec.LongPress, is ActionSpec.Swipe -> true
        else -> false
    }

    private fun isExitStep(step: SkillStep): Boolean =
        step.action is ActionSpec.Home || step.intent == StepIntent.GO_HOME || step.action is ActionSpec.Back

    private fun SemanticSkill.editableSummary(): String = buildString {
        append(summary()).append("\nStep IDs and actions:\n")
        steps.forEach { step ->
            append('[').append(step.id).append("] intent=").append(step.intent.name)
            append(" action=").append(step.action::class.simpleName)
            append(" description=").append(step.describeForEdit()).append('\n')
        }
    }

    private fun SkillStep.describeForEdit(): String =
        description.ifBlank { target.description.ifBlank { target.intentLabel } }

    /**
     * Rewrites the editable review goal and any temporal input semantics it states.
     * Arbitrary goal prose remains descriptive; the safe, deterministic current-time
     * intent is also compiled into the input step so the review field is not cosmetic.
     */
    fun rewriteGoal(skill: SemanticSkill, goal: String): SemanticSkill {
        val renamed = skill.copy(goal = goal)
        return if (hasCurrentMomentIntent(goal)) patchCurrentMoment(renamed, goal) ?: renamed else renamed
    }

    private fun patchCurrentMoment(skill: SemanticSkill, request: String): SemanticSkill? {
        if (!hasCurrentMomentIntent(request)) return null

        val writtenOn = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(skill.createdAt.takeIf { it > 0 } ?: time.nowMillis()),
            ZoneId.systemDefault(),
        )
        val variables = skill.variables.toMutableList()
        var changed = false

        fun normaliseVariable(name: String): Boolean {
            val index = variables.indexOfFirst { it.name == name }
            if (index < 0) return false
            val binding = variables[index].binding as? VariableBinding.RelativeDate ?: return false
            variables[index] = variables[index].copy(
                binding = binding.copy(offsetDays = 0),
                description = "the moment the automation runs",
            )
            return true
        }

        fun variableFor(located: TemporalText.Located): String {
            variables.indexOfFirst {
                val binding = it.binding as? VariableBinding.RelativeDate
                binding?.pattern == located.recognised.pattern && binding.offsetDays == 0
            }.takeIf { it >= 0 }?.let { return variables[it].name }

            val base = "now"
            val name = generateSequence(base) { previous ->
                val suffix = previous.removePrefix(base).toIntOrNull()?.plus(1) ?: 2
                "$base$suffix"
            }.first { candidate -> variables.none { it.name == candidate } }
            variables += SkillVariable(
                name = name,
                type = ValueType.DATE,
                binding = VariableBinding.RelativeDate(offsetDays = 0, pattern = located.recognised.pattern),
                description = "the moment the automation runs",
                exampleValue = located.value,
            )
            return name
        }

        val steps = skill.steps.map { step ->
            val input = step.action as? ActionSpec.InputText ?: return@map step
            val updatedValue = when (val ref = input.value) {
                is ValueRef.Literal -> {
                    val located = TemporalText.locate(ref.value, writtenOn) ?: return@map step
                    val name = variableFor(located)
                    ValueRef.Template(ref.value.replaceRange(located.range, "{$name}"))
                }
                is ValueRef.Variable -> {
                    if (!normaliseVariable(ref.name)) return@map step
                    ref
                }
                is ValueRef.Template -> {
                    val names = TEMPLATE_VARIABLE.findAll(ref.template).map { it.groupValues[1] }.toList()
                    if (names.map(::normaliseVariable).none { it }) return@map step
                    ref
                }
                is ValueRef.Constant -> return@map step
            }
            changed = true
            step.copy(action = input.copy(value = updatedValue))
        }
        return if (changed) skill.copy(variables = variables, steps = steps) else null
    }

    private fun SemanticSkill.summary(): String = buildString {
        append(name).append(": ").append(goal)
        append(". Trigger: ")
        append(
            when (val value = trigger) {
                TriggerSpec.Manual -> "manual"
                is TriggerSpec.Time -> value.describe()
                is TriggerSpec.Notification -> value.describe()
            },
        )
        if (constants.isNotEmpty()) {
            append(". Constants: ")
            append(constants.joinToString { "${it.name}=${it.value}" })
        }
        if (steps.isNotEmpty()) {
            append(". Steps: ")
            append(steps.joinToString(" -> ") { step ->
                step.description.ifBlank { step.target.description.ifBlank { step.target.intentLabel } }
            })
        }
    }

    private companion object {
        const val MIN_EDIT_CONFIDENCE = 0.65f
        const val MAX_NAME_LENGTH = 60
        val TIME_PATTERN = Regex("(?:^|\\s)([01]?\\d|2[0-3]):([0-5]\\d)(?:\\s|$)")
        val KOREAN_TIME_PATTERN = Regex("(?:^|\\s)([01]?\\d|2[0-3])\\s*시(?:\\s*([0-5]?\\d)\\s*분)?")
        val DESTINATION_NAMES = listOf("destination", "channel", "recipient", "target", "room")
        val STAY_POSTCONDITION_TERMS = listOf("remain", "stay", "foreground", "유지", "머무")
        const val MAX_GAMEPLAY_ACTIONS = 64
        const val GAMEPLAY_SETTLE_TIMEOUT_MS = 8_000L
        // Android's ICU regex engine (including API 30) requires the closing brace to
        // be escaped as well. The desktop JVM accepts a bare `}`, which let unit tests
        // pass while the release APK crashed during AppGraph construction.
        val TEMPLATE_VARIABLE = Regex("\\{([A-Za-z][A-Za-z0-9_]*)\\}")

        fun hasCurrentMomentIntent(value: String): Boolean {
            val normalised = value.lowercase()
            val koreanMoment = listOf("현재", "지금", "오늘").any(normalised::contains) &&
                listOf("날짜", "시간", "시각").any(normalised::contains)
            val englishMoment = listOf("current", "now", "today").any(normalised::contains) &&
                listOf("date", "time", "timestamp", "moment").any(normalised::contains)
            return koreanMoment || englishMoment
        }

    }
}

sealed interface SkillEditPreview {
    data class Ready(
        val original: SemanticSkill,
        val updated: SemanticSkill,
        val summary: String,
        val meaningChanged: Boolean,
        val changedStepIds: List<String>,
    ) : SkillEditPreview

    data class Rejected(val reason: String) : SkillEditPreview
}

sealed interface SkillEditApplyResult {
    data class Applied(val skill: SemanticSkill) : SkillEditApplyResult
    data class Rejected(val reason: String) : SkillEditApplyResult
}
