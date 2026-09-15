package com.autobile.runtime

import com.autobile.ai.provider.ProviderCapabilities
import com.autobile.ai.provider.StructuredInferenceProvider
import com.autobile.ai.provider.StructuredRequest
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.core.model.Bounds
import com.autobile.core.model.Direction
import com.autobile.core.model.PerceptionResult
import com.autobile.core.model.InferenceError
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.InferenceResult
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.UiNode
import com.autobile.runtime.control.ActionResult
import com.autobile.runtime.control.ScreenActuator
import com.autobile.runtime.perception.ScreenObserver
import com.autobile.runtime.perception.ScreenshotCapture

/**
 * A provider whose answers are supplied by the test.
 *
 * Keyed by the request label so one instance can serve a flow that asks several
 * different questions, and so a test can assert that a particular question was never
 * asked at all.
 */
class ScriptedProvider(
    override val tier: RuntimeTier = RuntimeTier.DEVICE_AI,
    override val id: String = "scripted",
    private val available: Boolean = true,
    private val answers: MutableMap<String, Any> = mutableMapOf(),
    private val supportsVision: Boolean = true,
) : StructuredInferenceProvider {

    val requestedLabels = mutableListOf<String>()

    private val failures = mutableMapOf<String, InferenceErrorKind>()
    private val transientFailures = mutableMapOf<String, InferenceErrorKind>()
    private val answerQueues = mutableMapOf<String, ArrayDeque<Any>>()

    fun answerWith(label: String, value: Any): ScriptedProvider {
        answers[label] = value
        return this
    }

    fun answerSequence(label: String, vararg values: Any): ScriptedProvider {
        answerQueues[label] = ArrayDeque(values.toList())
        return this
    }

    /** Fails this label every time, the way an unreachable or refusing runtime does. */
    fun failWith(label: String, kind: InferenceErrorKind): ScriptedProvider {
        failures[label] = kind
        return this
    }

    /** Fails once and then behaves, the way a model busy for a moment does. */
    fun failOnceWith(label: String, kind: InferenceErrorKind): ScriptedProvider {
        transientFailures[label] = kind
        return this
    }

    override suspend fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        available = available,
        supportsVision = supportsVision,
        supportsSystemPrompt = true,
        maxInputTokens = 8_000,
    )

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> structured(request: StructuredRequest<T>): InferenceResult<T> {
        requestedLabels += request.label
        transientFailures.remove(request.label)?.let { kind ->
            return InferenceResult(
                value = null,
                confidence = 0f,
                tier = tier,
                providerId = id,
                error = InferenceError(kind, "scripted transient ${kind.name.lowercase()}"),
            )
        }
        failures[request.label]?.let { kind ->
            return InferenceResult(
                value = null,
                confidence = 0f,
                tier = tier,
                providerId = id,
                error = InferenceError(kind, "scripted ${kind.name.lowercase()}"),
            )
        }
        val answer = answerQueues[request.label]?.removeFirstOrNull() ?: answers[request.label]
            ?: return InferenceResult(
                value = null,
                confidence = 0f,
                tier = tier,
                providerId = id,
                error = InferenceError(InferenceErrorKind.UNAVAILABLE, "no scripted answer for ${request.label}"),
            )
        return InferenceResult(answer as T, confidence = 0.9f, tier = tier, providerId = id)
    }
}

fun routerWith(vararg providers: StructuredInferenceProvider) = AiRuntimeRouter(providers.toList())

fun node(
    id: String,
    text: String? = null,
    resourceId: String? = null,
    clickable: Boolean = true,
    indexPath: List<Int> = emptyList(),
    bounds: Bounds = Bounds(0, 0, 400, 120),
    editable: Boolean = false,
    focused: Boolean = false,
    hint: String? = null,
    contentDescription: String? = null,
    className: String? = null,
) = UiNode(
    nodeId = id,
    text = text,
    resourceId = resourceId,
    clickable = clickable,
    indexPath = indexPath,
    bounds = bounds,
    editable = editable,
    focused = focused,
    hint = hint,
    contentDescription = contentDescription,
    className = className,
)

fun screen(
    packageName: String = "com.example.business",
    windowTitle: String = "Reports",
    vararg nodes: UiNode,
) = ScreenSnapshot(
    packageName = packageName,
    windowTitle = windowTitle,
    nodes = nodes.toList(),
    screenWidth = 1080,
    screenHeight = 2400,
)

/**
 * An in-memory screen that both observes and acts.
 *
 * Implementing both halves of the device surface in one object keeps a test's setup to
 * a single fixture, and lets an assertion check what was pressed against what was shown
 * at the time.
 */
class FakeScreen(
    private var current: ScreenSnapshot = ScreenSnapshot(),
    private val perception: PerceptionResult? = null,
    /** Swapped in after the first successful action, to model a screen transition. */
    private val nextScreen: ScreenSnapshot? = null,
    private val screenshot: ScreenshotCapture = ScreenshotCapture.Unavailable("not needed"),
    private val nextScreenshot: ScreenshotCapture? = null,
    private val rejectNodeClicks: Boolean = false,
    private val rejectFirstTextInput: Boolean = false,
) : ScreenObserver, ScreenActuator {

    val clicked = mutableListOf<String>()
    val typed = mutableListOf<Pair<String, String>>()
    val launched = mutableListOf<String>()
    val scrolled = mutableListOf<Direction>()
    val gestures = mutableListOf<String>()
    var backPresses: Int = 0
        private set
    var homePresses: Int = 0
        private set
    private var textInputAttempts = 0
    private var actionOccurred = false

    override suspend fun observe(settleMs: Long): PerceptionResult =
        perception ?: PerceptionResult.Success(current)

    override suspend fun observeStable(timeoutMs: Long, settleMs: Long): PerceptionResult =
        perception ?: PerceptionResult.Success(current)

    override suspend fun captureScreenshot(): ScreenshotCapture =
        if (actionOccurred) nextScreenshot ?: screenshot else screenshot

    override suspend fun click(node: UiNode): ActionResult {
        clicked += node.nodeId
        if (rejectNodeClicks) return ActionResult.Failed("node rejected click")
        advance()
        return ActionResult.Performed("click")
    }

    override suspend fun longPress(node: UiNode, durationMs: Long): ActionResult {
        clicked += node.nodeId
        advance()
        return ActionResult.Performed("long press")
    }

    override suspend fun tapAt(bounds: Bounds): ActionResult = ActionResult.Performed("tap")

    override suspend fun tapRatio(xRatio: Float, yRatio: Float): ActionResult {
        gestures += "tap"
        advance()
        return ActionResult.Performed("tap")
    }

    override suspend fun longPressRatio(xRatio: Float, yRatio: Float, durationMs: Long): ActionResult {
        gestures += "long_press"
        advance()
        return ActionResult.Performed("long press")
    }

    override suspend fun swipe(direction: Direction, distanceRatio: Float, durationMs: Long): ActionResult {
        gestures += "swipe"
        scrolled += direction
        return ActionResult.Performed("swipe")
    }

    override suspend fun swipeRatio(
        startXRatio: Float,
        startYRatio: Float,
        endXRatio: Float,
        endYRatio: Float,
        durationMs: Long,
    ): ActionResult {
        gestures += "swipe_ratio"
        advance()
        return ActionResult.Performed("visual swipe")
    }

    override suspend fun scroll(container: UiNode?, direction: Direction): ActionResult {
        scrolled += direction
        return ActionResult.Performed("scroll")
    }

    override suspend fun inputText(node: UiNode, value: String, clearExisting: Boolean): ActionResult {
        textInputAttempts++
        if (rejectFirstTextInput && textInputAttempts == 1) {
            return ActionResult.Failed("field rejected text")
        }
        typed += node.nodeId to value
        advance()
        return ActionResult.Performed("input")
    }

    override suspend fun inputTextAtFocus(value: String, clearExisting: Boolean): ActionResult {
        typed += "focused" to value
        advance()
        return ActionResult.Performed("focused input")
    }

    override fun pressBack(): ActionResult {
        backPresses++
        advance()
        return ActionResult.Performed("back")
    }

    override fun pressHome(): ActionResult {
        homePresses++
        advance()
        return ActionResult.Performed("home")
    }

    override fun launchApp(packageName: String, activity: String?): ActionResult {
        launched += packageName
        advance()
        return ActionResult.Performed("launch")
    }

    /** Moves to the follow-up screen, once, so a recovery tap can reveal a new target. */
    private fun advance() {
        actionOccurred = true
        nextScreen?.let { current = it }
    }
}
