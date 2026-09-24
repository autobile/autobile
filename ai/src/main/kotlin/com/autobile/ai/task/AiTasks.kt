package com.autobile.ai.task

import com.autobile.ai.provider.ResponseSchema
import com.autobile.ai.provider.boolOr
import com.autobile.ai.provider.floatOr
import com.autobile.ai.provider.intOr
import com.autobile.ai.provider.objectList
import com.autobile.ai.provider.stringList
import com.autobile.ai.provider.stringOr
import kotlinx.serialization.json.JsonObject

/**
 * The catalogue of questions Autobile asks a model.
 *
 * Almost every entry is deliberately narrow: identify one element, classify one screen,
 * read one value. That is what makes the same skill runnable on a small on-device model
 * and on a large hosted one, and what makes each answer cheap enough to validate before
 * acting on it.
 *
 * The exception is [agentTurn], which asks for the next few actions toward a goal. Even
 * there the application owns the loop: the model answers one turn at a time, and every
 * action it names is checked before anything is performed.
 */
object AiTasks {

    /**
     * The single system instruction shared by every task.
     *
     * Repeating a long preamble per call would waste a meaningful fraction of a small
     * context window, so this stays short and task prompts carry the specifics.
     */
    const val SYSTEM_INSTRUCTION: String =
        "You analyse Android user interfaces. Answer only with the requested JSON. " +
            "Never invent interface elements or values that are not present in the input. " +
            "If the input does not contain the answer, say so through the response fields " +
            "rather than guessing."

    // -- Element selection ----------------------------------------------------

    /**
     * Picks which of the listed elements matches a step's target.
     *
     * This is the workhorse of skill execution after a UI change: the cached locator
     * has stopped matching, but the meaning of the step has not, so the model is asked
     * to map that meaning onto one of the elements actually on screen.
     */
    val elementMatch = ResponseSchema(
        name = "ElementMatch",
        fieldGuide = """
            index: integer, the 0-based number of the matching element, or -1 if none match
            confidence: number between 0 and 1
            reason: short explanation, at most 15 words
        """.trimIndent(),
        example = """{"index": 3, "confidence": 0.88, "reason": "labelled Daily Sales under Reports"}""",
        parser = { json ->
            ElementMatch(
                index = json.intOr("index", -1),
                confidence = json.floatOr("confidence", 0f),
                reason = json.stringOr("reason"),
            )
        },
        validator = { match ->
            when {
                match.index < -1 -> "index out of range"
                match.confidence !in 0f..1f -> "confidence out of range"
                else -> null
            }
        },
    )

    fun elementMatchPrompt(targetDescription: String, synonyms: List<String>, renderedNodes: String): String =
        buildString {
            append("Which element performs this action?\n")
            append("Action: ").append(targetDescription).append('\n')
            if (synonyms.isNotEmpty()) {
                append("Also known as: ").append(synonyms.joinToString(", ")).append('\n')
            }
            append("\nElements:\n").append(renderedNodes)
            append("\n\nIf no element performs this action, answer with index -1.")
        }

    /**
     * Locates a target by looking at the screen, when there is no element to pick from.
     *
     * A game, a canvas-drawn interface, a video player: plenty of apps expose nothing an
     * accessibility tree can name, and an agent that can only choose from a list of
     * elements is simply unable to operate them. Coordinates are a fraction of the
     * screen rather than pixels, so an answer survives a different device.
     *
     * This is the expensive, last-resort path. Everything deterministic is tried first,
     * because a run that reasons about every step is slow, costly and less predictable
     * than one that follows what it was shown.
     */
    val pointMatch = ResponseSchema(
        name = "PointMatch",
        fieldGuide = """
            found: true or false
            x: number between 0 and 1, the horizontal position as a fraction of screen width
            y: number between 0 and 1, the vertical position as a fraction of screen height
            confidence: number between 0 and 1
            reason: short explanation, at most 15 words
        """.trimIndent(),
        example = """{"found": true, "x": 0.5, "y": 0.82, "confidence": 0.8, "reason": "claim button near the bottom"}""",
        parser = { json ->
            PointMatch(
                found = json.boolOr("found", false),
                xRatio = json.floatOr("x", -1f),
                yRatio = json.floatOr("y", -1f),
                confidence = json.floatOr("confidence", 0f),
                reason = json.stringOr("reason"),
            )
        },
        validator = { match ->
            when {
                !match.found -> null
                match.xRatio !in 0f..1f || match.yRatio !in 0f..1f -> "coordinates out of range"
                match.confidence !in 0f..1f -> "confidence out of range"
                else -> null
            }
        },
    )

    fun pointMatchPrompt(
        targetDescription: String,
        synonyms: List<String>,
        avoidedPoints: List<Pair<Float, Float>> = emptyList(),
    ): String = buildString {
        append("Look at this screen and find where to act.\n")
        append("Action: ").append(targetDescription).append('\n')
        if (synonyms.isNotEmpty()) {
            append("Also known as: ").append(synonyms.joinToString(", ")).append('\n')
        }
        append("\nAnswer with the point to touch, as fractions of the screen width and height ")
        append("measured from the top-left corner. Aim for the centre of the control.\n")
        append("Never select Android status/navigation controls, Home, Back, Recents, or an app-exit control ")
        append("unless the requested action explicitly names that control.\n")
        if (avoidedPoints.isNotEmpty()) {
            append("These points were already tried without reaching the expected state: ")
            append(avoidedPoints.joinToString { (x, y) -> "(${"%.2f".format(x)}, ${"%.2f".format(y)})" })
            append(". Choose a different valid control, or answer found false.\n")
        }
        append("If the screen does not offer this action, answer with found false.")
    }

    // -- Goal agent -------------------------------------------------------------

    /**
     * One turn of the goal agent: what the screen shows, and the next few actions.
     *
     * This is the one task that is not a narrow classification. It exists because a
     * recorded route cannot anticipate a reward pop-up, a new tutorial or a puzzle whose
     * next move depends on the board, and refusing to act on those is what made runs
     * fail. The application still owns the loop: every action named here is checked
     * against the screen, the system bars, the app policy and the risk gate before it
     * is performed, and anything rejected is reported back on the next turn rather than
     * ending the run.
     */
    val agentTurn = ResponseSchema(
        name = "AgentTurn",
        fieldGuide = """
            status: act, complete, or blocked
            observation: what the screen shows now, naming any pop-up, reward, ad, tutorial or dialog, at most 25 words
            actions: for act, 1 to $MAX_AGENT_BATCH actions performed in order. Each action has:
                     type: tap, long_press, swipe, input_text, scroll, back, wait, or open_app
                     element: number of a listed element to act on, or -1 to use x and y
                     x, y: target point as fractions of screen width and height from the top-left, when element is -1
                     endX, endY: swipe end point as fractions
                     durationMs: gesture or wait duration in milliseconds
                     text: exact text for input_text, otherwise ""
                     clearExisting: true to replace the field contents, false to append
                     direction: up, down, left or right for scroll
                     app: app name for open_app
                     label: the visible text or icon meaning of the control acted on
                     risk: none, or one of purchase, payment, transfer, subscription, booking, cancellation, delete, message_send, external_post, permission_change, account_change when the action commits one
            memory: compact facts that must survive to the next turn, such as progress or board state
            progress: what you are doing now, for the person watching, at most 12 words
            confidence: number between 0 and 1
            reason: short visible evidence for this decision
        """.trimIndent(),
        example = """{"status":"act","observation":"daily reward pop-up covers the board","actions":[{"type":"tap","element":4,"x":-1,"y":-1,"label":"Close","risk":"none"}],"memory":"row 1 needs 3 and 7","progress":"Closing the reward pop-up","confidence":0.84,"reason":"pop-up blocks the puzzle"}""",
        parser = { json ->
            val listed = json.objectList("actions").map(::parseAgentAction)
            // Smaller models often answer with a single flat action instead of a list.
            // Reading that shape costs nothing and saves a whole turn.
            val flat = json.stringOr("type").ifBlank { json.stringOr("action") }
                .takeIf { listed.isEmpty() && it.isNotBlank() }
                ?.let { parseAgentAction(json) }
            AgentTurn(
                status = AgentTurnStatus.parse(json.stringOr("status")),
                actions = (listed.ifEmpty { listOfNotNull(flat) }).take(MAX_AGENT_BATCH),
                observation = json.stringOr("observation"),
                memory = json.stringOr("memory"),
                progress = json.stringOr("progress"),
                confidence = json.floatOr("confidence", 0.5f),
                reason = json.stringOr("reason"),
            )
        },
        validator = { turn ->
            when {
                turn.confidence !in 0f..1f -> "confidence out of range"
                turn.status == AgentTurnStatus.ACT && turn.actions.none { it.type != AgentActionType.NONE } ->
                    "act requires at least one recognised action"
                else -> null
            }
        },
    )

    fun agentTurnPrompt(turn: AgentTurnContext): String = buildString {
        append("You operate an Android phone for the user. Decide the next actions from the current screen.\n")
        append("Objective: ").append(turn.objective).append('\n')
        append("Done when: ").append(turn.completionCriteria).append('\n')
        if (turn.runInstruction.isNotBlank()) {
            append("Instruction for this run (takes priority over the recorded route): ")
                .append(turn.runInstruction).append('\n')
        }
        if (turn.guidance.isNotEmpty()) {
            append("Standing instructions from the user:\n")
            turn.guidance.forEach { append("- ").append(it).append('\n') }
        }
        if (turn.referenceRoute.isNotEmpty()) {
            append("Route the user demonstrated (evidence of intent, not a script; skip or change steps when the screen differs):\n")
            turn.referenceRoute.forEachIndexed { index, line -> append(index + 1).append(". ").append(line).append('\n') }
        }
        if (turn.focus.isNotBlank()) append("Current focus: ").append(turn.focus).append('\n')
        if (turn.knownValues.isNotEmpty()) {
            append("Known values: ")
            append(turn.knownValues.entries.joinToString("; ") { (name, value) -> "$name=$value" })
            append('\n')
        }
        if (turn.currentDateTime.isNotBlank()) append("Now: ").append(turn.currentDateTime).append('\n')
        append("Turn ").append(turn.turnNumber).append(", actions left: ").append(turn.actionsLeft).append('\n')
        append("Foreground app: ").append(turn.foregroundApp)
        when {
            turn.isAutobile -> append(" (this is the assistant itself: never act on it; use open_app)")
            !turn.inTaskApp && turn.taskApps.isNotEmpty() ->
                append(" (outside the task apps ").append(turn.taskApps.joinToString())
                    .append("; use back or open_app to return unless this app is needed)")
        }
        append('\n')
        if (turn.recentActions.isNotEmpty()) {
            append("Recent actions and their visible effect:\n")
            turn.recentActions.forEach { append("- ").append(it).append('\n') }
        }
        if (turn.memory.isNotBlank()) append("Memory: ").append(turn.memory).append('\n')
        if (turn.feedback.isNotEmpty()) {
            append("Feedback from the executor about your previous answer:\n")
            turn.feedback.forEach { append("- ").append(it).append('\n') }
        }
        if (turn.elements.isNotBlank()) {
            append("Elements on screen (number, text, traits, centre as x,y fractions):\n").append(turn.elements).append('\n')
        } else {
            append("No accessible elements are listed for this screen")
            append(if (turn.hasImage) "; locate targets in the screenshot.\n" else ".\n")
        }
        if (turn.hasImage) append("The attached screenshot is the current screen.\n")
        append("Rules:\n")
        append("- Work toward the objective from what is visible now. Pop-ups, login or daily rewards, tutorials, ")
        append("rating prompts, ads and dialogs are normal: dismiss or accept them with the control that keeps the task going ")
        append("(close, X, skip, later, OK, collect), then continue.\n")
        append("- Prefer an element number when the control is listed; use x and y only for things drawn on a canvas or image.\n")
        append("- Return several actions only when none of them changes the screen layout for the next one, ")
        append("for example selecting a cell and then a number. Otherwise return one action and look again.\n")
        append("- Never repeat an action whose effect was 'no visible change'; choose a different control or approach.\n")
        append("- For puzzles and board games, read the whole board, keep its state in memory, ")
        append("and only play moves you can justify from that state.\n")
        append("- back is allowed for closing dialogs and returning; Home, Recents and the status or navigation bars are not.\n")
        append("- Do not buy, pay, subscribe, delete the user's data, send or post unless the objective requires it, and then set risk. ")
        append("Game moves, erasing a puzzle cell, and closing or cancelling a dialog are not risks.\n")
        append("- complete only when the screenshot or elements visibly prove every part of 'Done when'. Progress is not completion.\n")
        append("- blocked only when nothing on screen can move the task forward, such as a required password or a protected screen.\n")
        append("- Keep memory short, factual and free of secrets.")
    }

    private fun parseAgentAction(entry: JsonObject): AgentAction = AgentAction(
        type = AgentActionType.parse(entry.stringOr("type").ifBlank { entry.stringOr("action") }),
        element = entry.intOr("element", -1),
        x = entry.floatOr("x", -1f),
        y = entry.floatOr("y", -1f),
        endX = entry.floatOr("endX", -1f),
        endY = entry.floatOr("endY", -1f),
        durationMs = entry.intOr("durationMs", 0).toLong(),
        text = entry.stringOr("text"),
        clearExisting = entry.boolOr("clearExisting", true),
        direction = entry.stringOr("direction"),
        app = entry.stringOr("app"),
        label = entry.stringOr("label"),
        risk = entry.stringOr("risk"),
    )

    /** More actions than this per turn stops being a plan and starts being a macro. */
    const val MAX_AGENT_BATCH: Int = 6

    // -- Screen classification ------------------------------------------------

    /** Decides whether the current screen is the one a step expected to reach. */
    val screenMatch = ResponseSchema(
        name = "ScreenMatch",
        fieldGuide = """
            matches: true or false
            confidence: number between 0 and 1
            actual: short description of the screen actually shown, at most 12 words
        """.trimIndent(),
        example = """{"matches": false, "confidence": 0.91, "actual": "login screen asking for a password"}""",
        parser = { json ->
            ScreenMatch(
                matches = json.boolOr("matches"),
                confidence = json.floatOr("confidence", 0f),
                actual = json.stringOr("actual"),
            )
        },
    )

    fun screenMatchPrompt(expectation: String, screenDescription: String): String = buildString {
        append("Is the screen below the one described?\n")
        append("Expected: ").append(expectation).append("\n\n")
        append("Screen:\n").append(screenDescription)
    }

    // -- Value extraction -----------------------------------------------------

    /**
     * Reads one named field off a screen.
     *
     * The field name is part of the answer so a caller can detect the common failure
     * where a model returns a number from the wrong row.
     */
    val valueExtraction = ResponseSchema(
        name = "ValueExtraction",
        fieldGuide = """
            found: true or false
            value: the value exactly as displayed, or "" when not found
            fieldLabel: the label shown next to the value, or ""
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"found": true, "value": "2,481,000", "fieldLabel": "Net sales", "confidence": 0.93}""",
        parser = { json ->
            ExtractedValue(
                found = json.boolOr("found"),
                value = json.stringOr("value"),
                fieldLabel = json.stringOr("fieldLabel"),
                confidence = json.floatOr("confidence", 0f),
            )
        },
        validator = { extracted ->
            if (extracted.found && extracted.value.isBlank()) "found was true but value was empty" else null
        },
    )

    fun valueExtractionPrompt(fieldName: String, qualifiers: List<String>, screenDescription: String): String =
        buildString {
            append("Read one value from this screen.\n")
            append("Field: ").append(fieldName).append('\n')
            if (qualifiers.isNotEmpty()) {
                append("It must match all of: ").append(qualifiers.joinToString(", ")).append('\n')
            }
            append("\nScreen:\n").append(screenDescription)
            append("\n\nReturn the value exactly as displayed, including separators and units.")
            append(" If the field is not on this screen, set found to false.")
        }

    // -- Natural language commands --------------------------------------------

    /** Turns a spoken or typed instruction into a goal plus its parameters. */
    val commandIntent = ResponseSchema(
        name = "CommandIntent",
        fieldGuide = """
            goal: one sentence describing the outcome the user wants
            appHint: the app the task most likely involves, or ""
            parameters: array of {name, value} pairs mentioned in the request
            referencesCurrentScreen: true when the request relies on what is on screen now
            completionCriteria: visible evidence that proves the goal is finished
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"goal":"Save the attached PDF to the Work folder","appHint":"KakaoTalk","parameters":[{"name":"folder","value":"Work"}],"referencesCurrentScreen":false,"completionCriteria":"The PDF is visible in the Work folder","confidence":0.86}""",
        parser = { json ->
            CommandIntent(
                goal = json.stringOr("goal"),
                appHint = json.stringOr("appHint"),
                parameters = json.objectList("parameters").associate {
                    it.stringOr("name") to it.stringOr("value")
                }.filterKeys { it.isNotBlank() },
                referencesCurrentScreen = json.boolOr("referencesCurrentScreen"),
                completionCriteria = json.stringOr("completionCriteria"),
                confidence = json.floatOr("confidence", 0f),
            )
        },
        validator = { intent -> if (intent.goal.isBlank()) "goal was empty" else null },
    )

    fun commandIntentPrompt(command: String, screenDescription: String?): String = buildString {
        append("Interpret this request as an automation goal.\n")
        append("Request: ").append(command).append('\n')
        if (!screenDescription.isNullOrBlank()) {
            append("\nThe user is currently looking at:\n").append(screenDescription)
            append("\nResolve any words like \"this\" or \"it\" against that screen.")
        }
    }

    // -- Demonstration understanding ------------------------------------------

    /**
     * Infers what a demonstration was *for*, rather than what it consisted of.
     *
     * The distinction matters: "tap the third PDF" breaks the next day, while "open the
     * most recent settlement PDF" keeps working.
     */
    val goalInference = ResponseSchema(
        name = "GoalInference",
        fieldGuide = """
            name: a short name for this automation, at most 4 words
            goal: one sentence describing the repeatable outcome
            summary: a plain-language description the user will be asked to confirm
            executionMode: fixed_steps when replaying the demonstrated actions is sufficient,
                           visual_agent when the next correct action depends on fresh pixels
                           (games, puzzles, canvases, changing boards, or other dynamic scenes)
            completionCriteria: for visual_agent, concrete visible evidence that proves the
                                whole task is finished; never use mere screen change or progress
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"name":"Daily Sales","goal":"Report yesterday's net sales to the #daily-sales channel","summary":"Check yesterday's net sales and post it to #daily-sales","confidence":0.82}""",
        parser = { json ->
            InferredGoal(
                name = json.stringOr("name"),
                goal = json.stringOr("goal"),
                summary = json.stringOr("summary"),
                executionMode = DemonstrationExecutionMode.parse(json.stringOr("executionMode")),
                completionCriteria = json.stringOr("completionCriteria"),
                confidence = json.floatOr("confidence", 0f),
            )
        },
        validator = { goal ->
            when {
                goal.goal.isBlank() -> "goal was empty"
                goal.executionMode == DemonstrationExecutionMode.VISUAL_AGENT &&
                    goal.completionCriteria.isBlank() -> "visual task completion criteria were empty"
                else -> null
            }
        },
    )

    fun goalInferencePrompt(steps: String): String = buildString {
        append("A user demonstrated a task by performing these steps.\n")
        append("Describe the repeatable intent behind them, not the individual taps.\n\n")
        append(steps)
    }

    /** Separates the steps that carry intent from mis-taps and exploration. */
    val traceSegmentation = ResponseSchema(
        name = "TraceSegmentation",
        fieldGuide = """
            steps: array of {index, role, why} where role is one of essential, navigation, noise, observation
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"steps":[{"index":0,"role":"navigation","why":"opens the app"},{"index":1,"role":"noise","why":"went back immediately"}],"confidence":0.8}""",
        parser = { json ->
            TraceSegmentation(
                steps = json.objectList("steps").mapNotNull { entry ->
                    val index = entry.intOr("index", -1)
                    if (index < 0) return@mapNotNull null
                    SegmentedStep(
                        index = index,
                        role = StepRole.parse(entry.stringOr("role")),
                        why = entry.stringOr("why"),
                    )
                },
                confidence = json.floatOr("confidence", 0f),
            )
        },
    )

    fun traceSegmentationPrompt(steps: String): String = buildString {
        append("Classify each recorded step of this demonstration.\n")
        append("Mark a step noise when the user corrected themselves, browsed without acting,")
        append(" or immediately undid what they had just done.\n\n")
        append(steps)
    }

    /**
     * Decides which recorded literals were incidental to the day the demonstration was
     * made, and which were deliberate choices meant to stay fixed.
     */
    val variableAnalysis = ResponseSchema(
        name = "VariableAnalysis",
        fieldGuide = """
            variables: array of {name, observedValue, meaning, relativeDays} where relativeDays is
                       an integer offset from today for dates, or 0 when it is not a date
            constants: array of {name, value, why}
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"variables":[{"name":"date","observedValue":"2026-09-10","meaning":"yesterday","relativeDays":-1}],"constants":[{"name":"channel","value":"#daily-sales","why":"the fixed reporting destination"}],"confidence":0.79}""",
        parser = { json ->
            VariableAnalysis(
                variables = json.objectList("variables").map { entry ->
                    AnalysedVariable(
                        name = entry.stringOr("name"),
                        observedValue = entry.stringOr("observedValue"),
                        meaning = entry.stringOr("meaning"),
                        relativeDays = entry.intOr("relativeDays", 0),
                    )
                }.filter { it.name.isNotBlank() },
                constants = json.objectList("constants").map { entry ->
                    AnalysedConstant(
                        name = entry.stringOr("name"),
                        value = entry.stringOr("value"),
                        why = entry.stringOr("why"),
                    )
                }.filter { it.name.isNotBlank() },
                confidence = json.floatOr("confidence", 0f),
            )
        },
    )

    fun variableAnalysisPrompt(demonstrationDate: String, steps: String): String = buildString {
        append("This demonstration was recorded on ").append(demonstrationDate).append(".\n")
        append("Identify which recorded values would differ on a later run (variables) and")
        append(" which the user intended to keep fixed (constants).\n\n")
        append(steps)
    }

    // -- Recovery -------------------------------------------------------------

    /** Proposes the next move when a step's target can no longer be found. */
    val recoveryProposal = ResponseSchema(
        name = "RecoveryProposal",
        fieldGuide = """
            action: one of tap, scroll, back, wait, give_up
            index: element number to tap when action is tap, otherwise -1
            direction: up or down when action is scroll, otherwise ""
            reason: short explanation, at most 15 words
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"action":"tap","index":2,"direction":"","reason":"Reports likely contains the sales page","confidence":0.74}""",
        parser = { json ->
            RecoveryProposal(
                action = RecoveryAction.parse(json.stringOr("action")),
                index = json.intOr("index", -1),
                direction = json.stringOr("direction"),
                reason = json.stringOr("reason"),
                confidence = json.floatOr("confidence", 0f),
            )
        },
    )

    fun recoveryProposalPrompt(
        goal: String,
        stepDescription: String,
        screenDescription: String,
        renderedNodes: String,
        alreadyTried: List<String>,
    ): String = buildString {
        append("An automation cannot find what it needs. Propose one next move.\n")
        append("Overall goal: ").append(goal).append('\n')
        append("Current step: ").append(stepDescription).append("\n\n")
        append("Screen:\n").append(screenDescription).append("\n\n")
        append("Elements:\n").append(renderedNodes)
        if (alreadyTried.isNotEmpty()) {
            append("\n\nAlready tried without success: ").append(alreadyTried.joinToString("; "))
        }
        append("\n\nPropose one move only. Answer give_up if no move is likely to help.")
    }

    // -- Notifications --------------------------------------------------------

    /** Decides whether a notification means what a trigger is waiting for. */
    val notificationMatch = ResponseSchema(
        name = "NotificationMatch",
        fieldGuide = """
            matches: true or false
            confidence: number between 0 and 1
            extracted: array of {name, value} pairs worth passing to the automation
        """.trimIndent(),
        example = """{"matches":true,"confidence":0.9,"extracted":[{"name":"orderId","value":"81234"}]}""",
        parser = { json ->
            NotificationMatch(
                matches = json.boolOr("matches"),
                confidence = json.floatOr("confidence", 0f),
                extracted = json.objectList("extracted").associate {
                    it.stringOr("name") to it.stringOr("value")
                }.filterKeys { it.isNotBlank() },
            )
        },
    )

    fun notificationMatchPrompt(condition: String, title: String, text: String, packageName: String): String =
        buildString {
            append("Does this notification satisfy the condition?\n")
            append("Condition: ").append(condition).append("\n\n")
            append("From: ").append(packageName).append('\n')
            append("Title: ").append(title).append('\n')
            append("Body: ").append(text)
        }

    // -- Validation and editing ----------------------------------------------

    /** Judges whether a step's stated outcome actually happened. */
    val outcomeCheck = ResponseSchema(
        name = "OutcomeCheck",
        fieldGuide = """
            satisfied: true or false
            confidence: number between 0 and 1
            observed: what the screen shows instead, at most 15 words
        """.trimIndent(),
        example = """{"satisfied":true,"confidence":0.88,"observed":"message appears in the channel"}""",
        parser = { json ->
            OutcomeCheck(
                satisfied = json.boolOr("satisfied"),
                confidence = json.floatOr("confidence", 0f),
                observed = json.stringOr("observed"),
            )
        },
    )

    fun outcomeCheckPrompt(expectation: String, screenDescription: String): String = buildString {
        append("Did this happen?\n")
        append("Expected outcome: ").append(expectation).append("\n\n")
        append("Screen after the action:\n").append(screenDescription)
    }

    /**
     * Interprets a plain-language edit to an existing automation.
     *
     * [meaningChanged] is the field that matters: a request that alters *what* the
     * automation does is escalated to the user for confirmation instead of being
     * applied, because those are precisely the edits a misreading would make costly.
     */
    val skillEdit = ResponseSchema(
        name = "SkillEdit",
        fieldGuide = """
            field: one of trigger_time, trigger_notification, destination, value_field, input_text, name, behavior, guidance, unknown
            Use behavior for a request that adds, removes, or changes what execution steps do.
            Use guidance for a standing instruction about how to act during a run that the steps need not encode,
            such as handling pop-ups or rewards, preferring an option, or a condition to watch for.
            newValue: the replacement value as the user stated it
            behaviorMode: for behavior only, one of visual_until_complete, stay_in_app, step_operations, unsupported
            objective: for visual_until_complete, the language-independent task objective
            completionCriteria: for visual_until_complete, visible evidence that proves the objective is fully complete
            preserveExit: true when recorded exit steps must run only after completion; false when they must be removed
            exitStepIds: for behavior only, IDs of recorded steps that leave the controlled app
            operations: for step_operations, an ordered array of edits. Each item has:
                        kind (insert_before, insert_after, replace, delete), stepId (the anchor),
                        action (visual_task, click, long_press, input_text, swipe, wait, launch_app, back, home),
                        target, objective, completionCriteria, text, clearExisting, direction,
                        durationMs, and packageName.
                        Use visual_task whenever future actions depend on fresh screenshots.
            meaningChanged: true when this changes what the automation does, not just how
            summary: one sentence describing the change
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"field":"trigger_time","newValue":"08:30","meaningChanged":false,"summary":"Run at 08:30 instead of 09:00","confidence":0.9}""",
        parser = { json ->
            SkillEdit(
                field = SkillEditField.parse(json.stringOr("field")),
                newValue = json.stringOr("newValue"),
                meaningChanged = json.boolOr("meaningChanged"),
                summary = json.stringOr("summary"),
                confidence = json.floatOr("confidence", 0f),
                behaviorMode = BehaviorEditMode.parse(json.stringOr("behaviorMode")),
                objective = json.stringOr("objective"),
                completionCriteria = json.stringOr("completionCriteria"),
                preserveExit = json.boolOr("preserveExit"),
                exitStepIds = json.stringList("exitStepIds"),
                operations = json.objectList("operations").map { operation ->
                    SkillStepEdit(
                        kind = SkillStepEditKind.parse(operation.stringOr("kind")),
                        stepId = operation.stringOr("stepId"),
                        action = EditableStepAction.parse(operation.stringOr("action")),
                        target = operation.stringOr("target"),
                        objective = operation.stringOr("objective"),
                        completionCriteria = operation.stringOr("completionCriteria"),
                        durationMs = operation.intOr("durationMs", 500).toLong(),
                        packageName = operation.stringOr("packageName"),
                        text = operation.stringOr("text"),
                        clearExisting = operation.boolOr("clearExisting"),
                        direction = operation.stringOr("direction"),
                    )
                },
            )
        },
    )

    fun skillEditPrompt(currentSummary: String, request: String): String = buildString {
        append("The user wants to change an existing automation.\n")
        append("Current automation: ").append(currentSummary).append('\n')
        append("Requested change: ").append(request)
    }

    /** Assigns risk categories to a step so the risk engine can gate it. */
    val riskClassification = ResponseSchema(
        name = "RiskClassification",
        fieldGuide = """
            categories: array of zero or more of message_send, external_post, purchase, payment,
                        transfer, subscription, booking, cancellation, delete, permission_change,
                        account_change
            reason: short explanation, at most 12 words
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"categories":["message_send"],"reason":"posts a message to a channel","confidence":0.9}""",
        parser = { json ->
            RiskClassification(
                categories = json.stringList("categories"),
                reason = json.stringOr("reason"),
                confidence = json.floatOr("confidence", 0f),
            )
        },
    )

    fun riskClassificationPrompt(stepDescription: String, appLabel: String): String = buildString {
        append("Classify the risk of this automated action.\n")
        append("App: ").append(appLabel).append('\n')
        append("Action: ").append(stepDescription).append('\n')
        append("Return an empty array when the action only reads or navigates.")
    }
}

// -- Result types -------------------------------------------------------------

/** A place on screen to act, as fractions of its width and height. */
data class PointMatch(
    val found: Boolean,
    val xRatio: Float,
    val yRatio: Float,
    val confidence: Float,
    val reason: String,
)

/** Everything the goal agent is told on one turn. Prompt rendering stays in [AiTasks]. */
data class AgentTurnContext(
    val objective: String,
    val completionCriteria: String,
    val foregroundApp: String,
    val inTaskApp: Boolean,
    val taskApps: List<String>,
    val turnNumber: Int,
    val actionsLeft: Int,
    val elements: String,
    val hasImage: Boolean,
    val isAutobile: Boolean = false,
    val runInstruction: String = "",
    val guidance: List<String> = emptyList(),
    val referenceRoute: List<String> = emptyList(),
    val focus: String = "",
    val knownValues: Map<String, String> = emptyMap(),
    val recentActions: List<String> = emptyList(),
    val memory: String = "",
    val feedback: List<String> = emptyList(),
    val currentDateTime: String = "",
)

data class AgentTurn(
    val status: AgentTurnStatus,
    val actions: List<AgentAction>,
    val observation: String,
    val memory: String,
    val progress: String,
    val confidence: Float,
    val reason: String,
)

enum class AgentTurnStatus {
    ACT,
    COMPLETE,
    BLOCKED;

    companion object {
        fun parse(value: String): AgentTurnStatus = when (value.trim().lowercase()) {
            "act", "continue", "action" -> ACT
            "complete", "completed", "done" -> COMPLETE
            // Anything unrecognised is treated as the model being stuck, which the
            // executor answers with feedback rather than an action it did not ask for.
            else -> BLOCKED
        }
    }
}

data class AgentAction(
    val type: AgentActionType,
    val element: Int = -1,
    val x: Float = -1f,
    val y: Float = -1f,
    val endX: Float = -1f,
    val endY: Float = -1f,
    val durationMs: Long = 0,
    val text: String = "",
    val clearExisting: Boolean = true,
    val direction: String = "",
    val app: String = "",
    val label: String = "",
    val risk: String = "",
) {
    val hasPoint: Boolean get() = x in 0f..1f && y in 0f..1f
    val hasEndPoint: Boolean get() = endX in 0f..1f && endY in 0f..1f
}

enum class AgentActionType {
    TAP,
    LONG_PRESS,
    SWIPE,
    INPUT_TEXT,
    SCROLL,
    BACK,
    WAIT,
    OPEN_APP,
    NONE;

    companion object {
        fun parse(value: String): AgentActionType = when (value.trim().lowercase().replace('-', '_')) {
            "tap", "click", "press" -> TAP
            "long_press", "longpress", "long_click" -> LONG_PRESS
            "swipe", "drag" -> SWIPE
            "input_text", "type", "enter_text", "input" -> INPUT_TEXT
            "scroll" -> SCROLL
            "back", "go_back" -> BACK
            "wait" -> WAIT
            "open_app", "launch_app", "launch" -> OPEN_APP
            // Home, recents and anything invented stay unperformable.
            else -> NONE
        }
    }
}

data class ElementMatch(val index: Int, val confidence: Float, val reason: String) {
    val found: Boolean get() = index >= 0
}

data class ScreenMatch(val matches: Boolean, val confidence: Float, val actual: String)

data class ExtractedValue(
    val found: Boolean,
    val value: String,
    val fieldLabel: String,
    val confidence: Float,
)

data class CommandIntent(
    val goal: String,
    val appHint: String,
    val parameters: Map<String, String>,
    val referencesCurrentScreen: Boolean,
    val confidence: Float,
    val completionCriteria: String = "",
)

data class InferredGoal(
    val name: String,
    val goal: String,
    val summary: String,
    val confidence: Float,
    val executionMode: DemonstrationExecutionMode = DemonstrationExecutionMode.FIXED_STEPS,
    val completionCriteria: String = "",
)

enum class DemonstrationExecutionMode {
    FIXED_STEPS,
    VISUAL_AGENT;

    companion object {
        fun parse(value: String): DemonstrationExecutionMode = when (value.trim().lowercase()) {
            "visual_agent", "visual_task", "dynamic_visual" -> VISUAL_AGENT
            else -> FIXED_STEPS
        }
    }
}

data class TraceSegmentation(val steps: List<SegmentedStep>, val confidence: Float)

data class SegmentedStep(val index: Int, val role: StepRole, val why: String)

enum class StepRole {
    ESSENTIAL,
    NAVIGATION,
    NOISE,
    OBSERVATION;

    companion object {
        fun parse(value: String): StepRole = when (value.trim().lowercase()) {
            "essential" -> ESSENTIAL
            "navigation" -> NAVIGATION
            "noise" -> NOISE
            "observation" -> OBSERVATION
            // An unrecognised role must not silently delete a step from the skill.
            else -> ESSENTIAL
        }
    }
}

data class VariableAnalysis(
    val variables: List<AnalysedVariable>,
    val constants: List<AnalysedConstant>,
    val confidence: Float,
)

data class AnalysedVariable(
    val name: String,
    val observedValue: String,
    val meaning: String,
    val relativeDays: Int,
) {
    val isRelativeDate: Boolean get() = relativeDays != 0
}

data class AnalysedConstant(val name: String, val value: String, val why: String)

data class RecoveryProposal(
    val action: RecoveryAction,
    val index: Int,
    val direction: String,
    val reason: String,
    val confidence: Float,
)

enum class RecoveryAction {
    TAP,
    SCROLL,
    BACK,
    WAIT,
    GIVE_UP;

    companion object {
        fun parse(value: String): RecoveryAction = when (value.trim().lowercase()) {
            "tap", "click" -> TAP
            "scroll" -> SCROLL
            "back" -> BACK
            "wait" -> WAIT
            // Anything unrecognised stops the recovery rather than guessing an action.
            else -> GIVE_UP
        }
    }
}

data class NotificationMatch(
    val matches: Boolean,
    val confidence: Float,
    val extracted: Map<String, String>,
)

data class OutcomeCheck(val satisfied: Boolean, val confidence: Float, val observed: String)

data class SkillEdit(
    val field: SkillEditField,
    val newValue: String,
    val meaningChanged: Boolean,
    val summary: String,
    val confidence: Float,
    val behaviorMode: BehaviorEditMode = BehaviorEditMode.UNSUPPORTED,
    val objective: String = "",
    val completionCriteria: String = "",
    val preserveExit: Boolean = false,
    val exitStepIds: List<String> = emptyList(),
    val operations: List<SkillStepEdit> = emptyList(),
)

data class SkillStepEdit(
    val kind: SkillStepEditKind,
    val stepId: String,
    val action: EditableStepAction = EditableStepAction.NONE,
    val target: String = "",
    val objective: String = "",
    val completionCriteria: String = "",
    val durationMs: Long = 500,
    val packageName: String = "",
    val text: String = "",
    val clearExisting: Boolean = true,
    val direction: String = "",
)

enum class SkillStepEditKind {
    INSERT_BEFORE, INSERT_AFTER, REPLACE, DELETE, UNKNOWN;

    companion object {
        fun parse(value: String): SkillStepEditKind = when (value.trim().lowercase()) {
            "insert_before" -> INSERT_BEFORE
            "insert_after" -> INSERT_AFTER
            "replace", "update" -> REPLACE
            "delete", "remove" -> DELETE
            else -> UNKNOWN
        }
    }
}

enum class EditableStepAction {
    VISUAL_TASK, CLICK, LONG_PRESS, INPUT_TEXT, SWIPE, WAIT, LAUNCH_APP, BACK, HOME, NONE;

    companion object {
        fun parse(value: String): EditableStepAction = when (value.trim().lowercase()) {
            "visual_task", "visual_agent" -> VISUAL_TASK
            "click", "tap" -> CLICK
            "long_press", "long-press" -> LONG_PRESS
            "input_text", "type", "enter_text" -> INPUT_TEXT
            "swipe", "scroll" -> SWIPE
            "wait" -> WAIT
            "launch_app", "open_app" -> LAUNCH_APP
            "back" -> BACK
            "home" -> HOME
            else -> NONE
        }
    }
}

enum class BehaviorEditMode {
    VISUAL_UNTIL_COMPLETE,
    STAY_IN_APP,
    STEP_OPERATIONS,
    UNSUPPORTED;

    companion object {
        fun parse(value: String): BehaviorEditMode = when (value.trim().lowercase()) {
            "visual_until_complete" -> VISUAL_UNTIL_COMPLETE
            "stay_in_app" -> STAY_IN_APP
            "step_operations" -> STEP_OPERATIONS
            else -> UNSUPPORTED
        }
    }
}

enum class SkillEditField {
    TRIGGER_TIME,
    TRIGGER_NOTIFICATION,
    DESTINATION,
    VALUE_FIELD,
    INPUT_TEXT,
    NAME,
    BEHAVIOR,
    GUIDANCE,
    UNKNOWN;

    companion object {
        fun parse(value: String): SkillEditField = when (value.trim().lowercase()) {
            "trigger_time" -> TRIGGER_TIME
            "trigger_notification" -> TRIGGER_NOTIFICATION
            "destination" -> DESTINATION
            "value_field" -> VALUE_FIELD
            "input_text" -> INPUT_TEXT
            "name" -> NAME
            "behavior" -> BEHAVIOR
            "guidance", "instruction" -> GUIDANCE
            else -> UNKNOWN
        }
    }
}

data class RiskClassification(
    val categories: List<String>,
    val reason: String,
    val confidence: Float,
)
