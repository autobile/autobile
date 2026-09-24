package com.autobile.ai.task

import com.autobile.ai.provider.JsonExtractor
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Schemas are the boundary between model output and actions taken on a user's phone,
 * so they have to reject malformed answers rather than pass them through.
 */
class AiTaskSchemaTest {

    private fun <T : Any> parse(schema: com.autobile.ai.provider.ResponseSchema<T>, raw: String): T? =
        JsonExtractor.extract(raw)?.let { schema.parse(it) }

    @Test
    fun `element match parses a selection`() {
        val match = parse(AiTasks.elementMatch, """{"index":3,"confidence":0.88,"reason":"under Reports"}""")!!
        assertThat(match.found).isTrue()
        assertThat(match.index).isEqualTo(3)
    }

    @Test
    fun `element match represents no match without inventing one`() {
        val match = parse(AiTasks.elementMatch, """{"index":-1,"confidence":0.2,"reason":"not present"}""")!!
        assertThat(match.found).isFalse()
    }

    @Test
    fun `element match rejects an out of range confidence`() {
        val match = parse(AiTasks.elementMatch, """{"index":1,"confidence":4.0}""")!!
        assertThat(AiTasks.elementMatch.validate(match)).isNotNull()
    }

    @Test
    fun `value extraction rejects a found flag with no value`() {
        val extracted = parse(AiTasks.valueExtraction, """{"found":true,"value":"","fieldLabel":"Net"}""")!!
        assertThat(AiTasks.valueExtraction.validate(extracted)).isNotNull()
    }

    @Test
    fun `value extraction keeps display formatting intact`() {
        val extracted = parse(
            AiTasks.valueExtraction,
            """{"found":true,"value":"2,481,000","fieldLabel":"Net sales","confidence":0.9}""",
        )!!
        assertThat(extracted.value).isEqualTo("2,481,000")
        assertThat(AiTasks.valueExtraction.validate(extracted)).isNull()
    }

    @Test
    fun `goal inference rejects an empty goal`() {
        val goal = parse(AiTasks.goalInference, """{"name":"Something","goal":"","summary":""}""")!!
        assertThat(AiTasks.goalInference.validate(goal)).isNotNull()
    }

    @Test
    fun `visual goal requires explicit completion evidence`() {
        val invalid = parse(
            AiTasks.goalInference,
            """{"name":"Sudoku","goal":"Solve it","executionMode":"visual_agent","completionCriteria":"","confidence":0.9}""",
        )!!
        assertThat(AiTasks.goalInference.validate(invalid)).isNotNull()

        val valid = parse(
            AiTasks.goalInference,
            """{"name":"Sudoku","goal":"Solve it","executionMode":"visual_agent","completionCriteria":"Success screen and no empty cells","confidence":0.9}""",
        )!!
        assertThat(valid.executionMode).isEqualTo(DemonstrationExecutionMode.VISUAL_AGENT)
        assertThat(AiTasks.goalInference.validate(valid)).isNull()
    }

    @Test
    fun `variable analysis recognises a relative date`() {
        val analysis = parse(
            AiTasks.variableAnalysis,
            """{"variables":[{"name":"date","observedValue":"2026-09-10","meaning":"yesterday","relativeDays":-1}],
               "constants":[{"name":"channel","value":"#daily-sales","why":"fixed"}],"confidence":0.8}""",
        )!!
        assertThat(analysis.variables).hasSize(1)
        assertThat(analysis.variables.first().isRelativeDate).isTrue()
        assertThat(analysis.constants.first().value).isEqualTo("#daily-sales")
    }

    @Test
    fun `variable analysis drops unnamed entries`() {
        val analysis = parse(
            AiTasks.variableAnalysis,
            """{"variables":[{"name":"","observedValue":"x"}],"constants":[]}""",
        )!!
        assertThat(analysis.variables).isEmpty()
    }

    @Test
    fun `an unrecognised segmentation role keeps the step rather than discarding it`() {
        val segmentation = parse(
            AiTasks.traceSegmentation,
            """{"steps":[{"index":0,"role":"who knows","why":""}],"confidence":0.5}""",
        )!!
        assertThat(segmentation.steps.first().role).isEqualTo(StepRole.ESSENTIAL)
    }

    @Test
    fun `an unrecognised recovery action gives up rather than guessing`() {
        val proposal = parse(AiTasks.recoveryProposal, """{"action":"teleport","index":9}""")!!
        assertThat(proposal.action).isEqualTo(RecoveryAction.GIVE_UP)
    }

    @Test
    fun `recovery proposals parse a scroll direction`() {
        val proposal = parse(
            AiTasks.recoveryProposal,
            """{"action":"scroll","index":-1,"direction":"down","reason":"list continues","confidence":0.7}""",
        )!!
        assertThat(proposal.action).isEqualTo(RecoveryAction.SCROLL)
        assertThat(proposal.direction).isEqualTo("down")
    }

    @Test
    fun `skill edit flags a change of meaning`() {
        val edit = parse(
            AiTasks.skillEdit,
            """{"field":"value_field","newValue":"net sales","meaningChanged":true,"summary":"Report net instead of gross"}""",
        )!!
        assertThat(edit.meaningChanged).isTrue()
        assertThat(edit.field).isEqualTo(SkillEditField.VALUE_FIELD)
    }

    @Test
    fun `skill edit parses structural step operations`() {
        val edit = parse(
            AiTasks.skillEdit,
            """{"field":"behavior","newValue":"finish the puzzle","behaviorMode":"step_operations","meaningChanged":true,"operations":[{"kind":"replace","stepId":"play","action":"visual_task","objective":"Solve the puzzle","completionCriteria":"Success is visible"}]}""",
        )!!
        assertThat(edit.behaviorMode).isEqualTo(BehaviorEditMode.STEP_OPERATIONS)
        assertThat(edit.operations).hasSize(1)
        assertThat(edit.operations.first().kind).isEqualTo(SkillStepEditKind.REPLACE)
        assertThat(edit.operations.first().action).isEqualTo(EditableStepAction.VISUAL_TASK)
    }

    @Test
    fun `notification match extracts trigger parameters`() {
        val match = parse(
            AiTasks.notificationMatch,
            """{"matches":true,"confidence":0.9,"extracted":[{"name":"orderId","value":"81234"}]}""",
        )!!
        assertThat(match.matches).isTrue()
        assertThat(match.extracted["orderId"]).isEqualTo("81234")
    }

    @Test
    fun `prompt contract asks for bare json`() {
        val contract = AiTasks.elementMatch.promptContract()
        assertThat(contract).contains("JSON only")
        assertThat(contract).contains("index")
    }

    @Test
    fun `agent turn reads a batch of actions in order`() {
        val turn = parse(
            AiTasks.agentTurn,
            """{"status":"act","observation":"board","actions":[{"type":"tap","element":-1,"x":0.31,"y":0.42,"label":"r3c2"},{"type":"tap","x":0.55,"y":0.86,"label":"7"}],"memory":"r3c2=7","progress":"Filling row 3","confidence":0.7,"reason":"only 7 fits"}""",
        )!!
        assertThat(AiTasks.agentTurn.validate(turn)).isNull()
        assertThat(turn.status).isEqualTo(AgentTurnStatus.ACT)
        assertThat(turn.actions.map { it.label }).containsExactly("r3c2", "7").inOrder()
        assertThat(turn.actions.first().hasPoint).isTrue()
        assertThat(turn.memory).isEqualTo("r3c2=7")
    }

    @Test
    fun `agent turn accepts a single flat action from a smaller model`() {
        val turn = parse(
            AiTasks.agentTurn,
            """{"status":"act","action":"click","element":4,"label":"Close","confidence":0.8}""",
        )!!
        assertThat(AiTasks.agentTurn.validate(turn)).isNull()
        assertThat(turn.actions.single().type).isEqualTo(AgentActionType.TAP)
        assertThat(turn.actions.single().element).isEqualTo(4)
    }

    @Test
    fun `agent turn rejects acting without a recognised action`() {
        val turn = parse(AiTasks.agentTurn, """{"status":"act","actions":[{"type":"home"}],"confidence":0.8}""")!!
        assertThat(AiTasks.agentTurn.validate(turn)).isNotNull()
    }

    @Test
    fun `agent turn caps how many actions one answer may carry`() {
        val many = (1..20).joinToString(",") { """{"type":"wait","durationMs":100}""" }
        val turn = parse(AiTasks.agentTurn, """{"status":"act","actions":[$many],"confidence":0.8}""")!!
        assertThat(turn.actions).hasSize(AiTasks.MAX_AGENT_BATCH)
    }

    @Test
    fun `an unrecognised agent status is read as stuck rather than as an action`() {
        val turn = parse(AiTasks.agentTurn, """{"status":"thinking","confidence":0.5}""")!!
        assertThat(turn.status).isEqualTo(AgentTurnStatus.BLOCKED)
    }

    @Test
    fun `agent prompt carries the user's instructions and the demonstrated route`() {
        val prompt = AiTasks.agentTurnPrompt(
            AgentTurnContext(
                objective = "Solve today's sudoku",
                completionCriteria = "Solved board visible",
                foregroundApp = "com.example.sudoku",
                inTaskApp = true,
                taskApps = listOf("com.example.sudoku"),
                turnNumber = 3,
                actionsLeft = 97,
                elements = "0. \"Close\" [clickable] @0.90,0.12",
                hasImage = true,
                runInstruction = "Play on hard",
                guidance = listOf("Close the daily reward if it appears"),
                referenceRoute = listOf("Opening Sudoku", "Tapping New game"),
                feedback = listOf("Not performed: tap @0.50,0.99: system bar"),
            ),
        )
        assertThat(prompt).contains("Instruction for this run (takes priority over the recorded route): Play on hard")
        assertThat(prompt).contains("- Close the daily reward if it appears")
        assertThat(prompt).contains("2. Tapping New game")
        assertThat(prompt).contains("Not performed: tap @0.50,0.99: system bar")
        assertThat(prompt).contains("0. \"Close\" [clickable] @0.90,0.12")
        assertThat(prompt).contains("The attached screenshot is the current screen.")
    }

    @Test
    fun `skill edits can be kept as standing guidance`() {
        val edit = parse(
            AiTasks.skillEdit,
            """{"field":"guidance","newValue":"Close reward pop-ups","meaningChanged":false,"summary":"Handle pop-ups","confidence":0.9}""",
        )!!
        assertThat(edit.field).isEqualTo(SkillEditField.GUIDANCE)
    }
}
