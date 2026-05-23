package com.lusail.stadium.agent

import kotlinx.serialization.*
import kotlinx.serialization.json.*

// ─────────────────────────────────────────────────────────────
// Domain Model — Deserialized from Gemma JSON output
// ─────────────────────────────────────────────────────────────

/**
 * Top-level response from the on-device model.
 * Always an array of exactly 2–3 Bubble objects.
 */
@Serializable
data class BubbleResponse(
    @SerialName("bubbles") // we parse the array root; this is the list
    val bubbles: List<Bubble>
)

@Serializable
data class Bubble(
    val label: String,
    val icon: String,
    val agent: String,          // routing key: payment | navigate | food | info | accessibility | entertainment
    val payload: JsonObject     // kept as raw JSON; dispatched to typed handlers after parsing
)

// ─────────────────────────────────────────────────────────────
// Agent Dispatcher Interface
// ─────────────────────────────────────────────────────────────

/**
 * Each agent handler implements this interface.
 * The router calls [handle] on the main thread after validation,
 * and the handler is responsible for any async work or UI transitions.
 */
interface AgentHandler {
    /** Unique routing key matching Bubble.agent */
    val key: String

    /**
     * Execute the action for this bubble.
     * @param label  The user-facing label (for UI confirmation).
     * @param icon   The emoji icon (for UI transitions).
     * @param payload Raw JSON payload — handler must parse its own contract.
     * @param context The full scan context that triggered this response (read-only).
     * @return true if the action was accepted and initiated; false if the payload was invalid.
     */
    fun handle(label: String, icon: String, payload: JsonObject, context: ScanContext): Boolean
}

// ─────────────────────────────────────────────────────────────
// Scan Context (passed through from the scan subsystem)
// ─────────────────────────────────────────────────────────────

/**
 * Immutable context snapshot captured when the user scanned.
 * Available to every handler for additional decision-making.
 */
data class ScanContext(
    val scan: ScanData,
    val match: MatchData,
    val user: UserData
)

@Serializable
data class ScanData(
    val beacon_type: String,
    // Parking
    val lot_id: String? = null,
    val lot_name: String? = null,
    val capacity_pct: Int? = null,
    val shuttle_wait_min: Int? = null,
    val nearest_gate: String? = null,
    val distance_to_gate_m: Int? = null,
    // Ticket gate
    val gate_id: String? = null,
    val gate_name: String? = null,
    val queue_length: String? = null,
    val estimated_wait_min: Int? = null,
    val open_lanes: Int? = null,
    val has_accessible_lane: Boolean? = null,
    // Concession
    val zone: String? = null,
    val stand_name: String? = null,
    val cuisine: String? = null,
    val queue_density: String? = null,
    val payment_types: List<String>? = null,
    val nearest_section: Int? = null,
    // Section entrance
    val section: Int? = null,
    val level: Int? = null,
    val category: Int? = null,
    val is_supporters_section: Boolean? = null,
    val nearest_concessions: List<String>? = null,
    val nearest_restroom_sections: List<Int>? = null,
    val nearest_prayer_room: String? = null,
    val nearest_exit: String? = null,
    // Info kiosk
    val kiosk_id: String? = null,
    val location: String? = null,
    val services: List<String>? = null,
    val staffed: Boolean? = null,
    val languages: List<String>? = null,
    // Exit gate
    val crowd_density: String? = null,
    val exit_wait_min: Int? = null,
    val alternative_gate: String? = null,
    val transport_options: List<String>? = null,
    val metro_status: String? = null,
    // Hospitality
    val tier: String? = null,
    val lounge_name: String? = null,
    val floor: String? = null,
    val suite_number: Int? = null,
    val catering_active: Boolean? = null,
    val dedicated_butler: String? = null,
    // Accessibility
    val services_available: List<String>? = null,
    val elevator_status: String? = null,
    val wheelchair_spaces_available: Int? = null
)

@Serializable
data class MatchData(
    val fixture: String? = null,
    val kickoff: String? = null,
    val competition: String? = null,
    val minutes_to_kickoff: Int? = null,
    val half: String? = null,
    val score: String? = null,
    val final_score: String? = null,
    val status: String? = null,
    val minutes_since_full_time: Int? = null
)

@Serializable
data class UserData(
    val language: String? = null,
    val has_ticket: Boolean? = null,
    val ticket_gate: String? = null,
    val ticket_section: String? = null,
    val ticket_category: Int? = null,
    val ticket_row: String? = null,
    val ticket_seat: String? = null,
    val hospitality_tier: String? = null,
    val dietary_preferences: List<String>? = null,
    val ticket_accessibility: String? = null,
    val accessibility_needs: List<String>? = null
)

// ─────────────────────────────────────────────────────────────
// Router
// ─────────────────────────────────────────────────────────────

/**
 * Parses the Gemma model's raw JSON output and dispatches each
 * bubble to the appropriate [AgentHandler].
 *
 * Lifecycle:
 *   1. User scans QR/NFC → ScanContext is built
 *   2. ScanContext is injected into the model's system prompt
 *   3. Model returns raw JSON string
 *   4. AgentRouter.route() is called with that string
 *   5. Each bubble is validated and dispatched to its handler
 */
class AgentRouter(
    private val handlers: Map<String, AgentHandler>,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
    }
) {

    /**
     * Result of routing a single bubble.
     */
    sealed class RouteResult {
        data class Dispatched(val bubbleIndex: Int, val agent: String, val label: String) : RouteResult()
        data class ValidationError(val bubbleIndex: Int, val reason: String) : RouteResult()
        data class UnknownAgent(val bubbleIndex: Int, val agent: String) : RouteResult()
        data class HandlerRejected(val bubbleIndex: Int, val agent: String, val label: String) : RouteResult()
    }

    /**
     * Top-level entry point. Parses the raw model output, validates
     * the array cardinality, and dispatches each bubble.
     *
     * @param rawJson   The raw string returned by the Gemma model.
     * @param context   The scan context that produced this response.
     * @return List of results, one per bubble.
     * @throws JsonDecodingException if the root JSON is malformed.
     */
    fun route(rawJson: String, context: ScanContext): List<RouteResult> {
        // 1. Parse root JSON array
        val rootElement = json.parseToJsonElement(rawJson)
        if (rootElement !is JsonArray) {
            throw JsonDecodingException("Root must be a JSON array, got ${rootElement::class.simpleName}")
        }

        val bubblesJson = rootElement
        val bubbleCount = bubblesJson.size

        // 2. Validate cardinality (enforce 2–3)
        if (bubbleCount < 2 || bubbleCount > 3) {
            throw JsonDecodingException("Expected 2–3 bubbles, got $bubbleCount")
        }

        // 3. Dispatch each bubble
        val results = mutableListOf<RouteResult>()
        for ((index, element) in bubblesJson.withIndex()) {
            if (element !is JsonObject) {
                results.add(RouteResult.ValidationError(index, "Bubble must be a JSON object"))
                continue
            }
            results.add(dispatchBubble(index, element, context))
        }

        return results
    }

    /**
     * Validates a single bubble object and dispatches it.
     */
    private fun dispatchBubble(
        index: Int,
        bubbleJson: JsonObject,
        context: ScanContext
    ): RouteResult {
        // Extract required top-level fields
        val label = bubbleJson["label"]?.jsonPrimitive?.contentOrNull
            ?: return RouteResult.ValidationError(index, "Missing or non-string 'label'")

        val icon = bubbleJson["icon"]?.jsonPrimitive?.contentOrNull
            ?: return RouteResult.ValidationError(index, "Missing or non-string 'icon'")

        val agentKey = bubbleJson["agent"]?.jsonPrimitive?.contentOrNull
            ?: return RouteResult.ValidationError(index, "Missing or non-string 'agent'")

        val payload = bubbleJson["payload"]?.jsonObject
            ?: return RouteResult.ValidationError(index, "Missing or non-object 'payload'")

        // Validate label length (2–28 chars)
        if (label.length < 2 || label.length > 28) {
            return RouteResult.ValidationError(index, "'label' must be 2–28 characters, got ${label.length}")
        }

        // Validate agent key
        if (agentKey !in VALID_AGENTS) {
            return RouteResult.UnknownAgent(index, agentKey)
        }

        // Look up handler
        val handler = handlers[agentKey]
            ?: return RouteResult.UnknownAgent(index, agentKey)

        // Dispatch
        return try {
            val accepted = handler.handle(label, icon, payload, context)
            if (accepted) {
                RouteResult.Dispatched(index, agentKey, label)
            } else {
                RouteResult.HandlerRejected(index, agentKey, label)
            }
        } catch (e: Exception) {
            RouteResult.ValidationError(
                index,
                "Handler '${agentKey}' threw: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    companion object {
        /** All valid agent routing keys recognized by the system. */
        val VALID_AGENTS: Set<String> = setOf(
            "payment", "navigate", "food", "info", "accessibility", "entertainment"
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Concrete Agent Handlers (Stubs)
// ─────────────────────────────────────────────────────────────

/**
 * Routes to the native payment sheet (Apple Pay / Google Wallet / card).
 * Expects payload: amount (optional), merchant, currency=QAR, tap_to_pay.
 */
class PaymentAgentHandler : AgentHandler {
    override val key = "payment"

    override fun handle(label: String, icon: String, payload: JsonObject, context: ScanContext): Boolean {
        val merchant = payload["merchant"]?.jsonPrimitive?.contentOrNull ?: return false
        val currency = payload["currency"]?.jsonPrimitive?.contentOrNull
        if (currency != "QAR") return false

        val amount = payload["amount"]?.jsonPrimitive?.doubleOrNull
        val tapToPay = payload["tap_to_pay"]?.jsonPrimitive?.booleanOrNull ?: false

        // Platform-specific: launch payment activity / view controller
        PaymentSheetManager.launch(
            amount = amount,
            merchant = merchant,
            currency = "QAR",
            tapToPay = tapToPay,
            confirmLabel = label
        )
        return true
    }
}

/**
 * Routes to the indoor/outdoor navigation engine.
 * Expects payload: destination, distance_m, level, landmarks (optional array).
 */
class NavigateAgentHandler : AgentHandler {
    override val key = "navigate"

    override fun handle(label: String, icon: String, payload: JsonObject, context: ScanContext): Boolean {
        val destination = payload["destination"]?.jsonPrimitive?.contentOrNull ?: return false
        val distanceM = payload["distance_m"]?.jsonPrimitive?.doubleOrNull ?: return false
        val level = payload["level"]?.jsonPrimitive?.intOrNull ?: return false

        val landmarks: List<String> = payload["landmarks"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.contentOrNull
        } ?: emptyList()

        NavigationEngine.navigateTo(
            destination = destination,
            distanceMeters = distanceM,
            level = level,
            landmarks = landmarks,
            sourceContext = context.scan
        )
        return true
    }
}

/**
 * Routes to the food ordering flow.
 * Expects payload: concession (A-H), menu_url, wait_min, pickup_section.
 */
class FoodAgentHandler : AgentHandler {
    override val key = "food"

    override fun handle(label: String, icon: String, payload: JsonObject, context: ScanContext): Boolean {
        val concession = payload["concession"]?.jsonPrimitive?.contentOrNull
        if (concession == null || concession !in "ABCDEFGH") return false

        val menuUrl = payload["menu_url"]?.jsonPrimitive?.contentOrNull
        val waitMin = payload["wait_min"]?.jsonPrimitive?.doubleOrNull
        val pickupSection = payload["pickup_section"]?.jsonPrimitive?.intOrNull

        FoodOrderManager.openMenu(
            concession = concession,
            menuUrl = menuUrl,
            estimatedWaitMin = waitMin,
            pickupSection = pickupSection,
            userSection = context.user.ticket_section
        )
        return true
    }
}

/**
 * Routes to the info card overlay.
 * Expects payload: topic, body, deep_link.
 */
class InfoAgentHandler : AgentHandler {
    override val key = "info"

    override fun handle(label: String, icon: String, payload: JsonObject, context: ScanContext): Boolean {
        val topic = payload["topic"]?.jsonPrimitive?.contentOrNull ?: return false
        val body = payload["body"]?.jsonPrimitive?.contentOrNull ?: return false
        val deepLink = payload["deep_link"]?.jsonPrimitive?.contentOrNull

        InfoOverlayManager.show(
            topic = topic,
            body = body,
            deepLink = deepLink,
            bubbleLabel = label
        )
        return true
    }
}

/**
 * Routes to accessibility services.
 * Expects payload: service, location, staff_alert.
 */
class AccessibilityAgentHandler : AgentHandler {
    override val key = "accessibility"

    override fun handle(label: String, icon: String, payload: JsonObject, context: ScanContext): Boolean {
        val service = payload["service"]?.jsonPrimitive?.contentOrNull ?: return false
        val location = payload["location"]?.jsonPrimitive?.contentOrNull ?: return false
        val staffAlert = payload["staff_alert"]?.jsonPrimitive?.booleanOrNull ?: false

        // Validate service enum
        val validServices = setOf(
            "wheelchair", "hearing_loop", "sensory_room",
            "companion_ticket", "elevator", "audio_description"
        )
        if (service !in validServices) return false

        AccessibilityServiceManager.request(
            service = service,
            location = location,
            notifyStaff = staffAlert,
            userNeeds = context.user.accessibility_needs ?: emptyList()
        )
        return true
    }
}

/**
 * Routes to entertainment / fan engagement features.
 * Expects payload: type, title, url.
 */
class EntertainmentAgentHandler : AgentHandler {
    override val key = "entertainment"

    override fun handle(label: String, icon: String, payload: JsonObject, context: ScanContext): Boolean {
        val type = payload["type"]?.jsonPrimitive?.contentOrNull ?: return false
        val title = payload["title"]?.jsonPrimitive?.contentOrNull ?: return false
        val url = payload["url"]?.jsonPrimitive?.contentOrNull

        val validTypes = setOf("fan_cam", "trivia", "chant", "selfie_frame", "live_poll", "replay")
        if (type !in validTypes) return false

        EntertainmentManager.launch(
            contentType = type,
            title = title,
            url = url
        )
        return true
    }
}

// ─────────────────────────────────────────────────────────────
// Wiring — Register all handlers
// ─────────────────────────────────────────────────────────────

/**
 * Builds the handler registry. Called once at app startup.
 */
fun buildAgentRouter(): AgentRouter {
    val handlers: Map<String, AgentHandler> = listOf(
        PaymentAgentHandler(),
        NavigateAgentHandler(),
        FoodAgentHandler(),
        InfoAgentHandler(),
        AccessibilityAgentHandler(),
        EntertainmentAgentHandler()
    ).associateBy { it.key }

    return AgentRouter(handlers = handlers)
}

// ─────────────────────────────────────────────────────────────
// Platform-Abstraction Interfaces (implemented per-platform)
// ─────────────────────────────────────────────────────────────

/**
 * Platform-specific payment sheet launcher.
 * Android: launches a BottomSheet with PaymentFragment.
 * iOS: presents PKPaymentAuthorizationViewController or custom sheet.
 */
interface PaymentSheetManager {
    fun launch(
        amount: Double?,
        merchant: String,
        currency: String,
        tapToPay: Boolean,
        confirmLabel: String
    )

    companion object {
        // Injected via DI or platform entry point
        lateinit var instance: PaymentSheetManager
        fun launch(amount: Double?, merchant: String, currency: String, tapToPay: Boolean, confirmLabel: String) {
            instance.launch(amount, merchant, currency, tapToPay, confirmLabel)
        }
    }
}

/** Indoor/outdoor AR or map-based navigation. */
interface NavigationEngine {
    fun navigateTo(
        destination: String,
        distanceMeters: Double,
        level: Int,
        landmarks: List<String>,
        sourceContext: ScanData
    )

    companion object {
        lateinit var instance: NavigationEngine
        fun navigateTo(destination: String, distanceMeters: Double, level: Int, landmarks: List<String>, sourceContext: ScanData) {
            instance.navigateTo(destination, distanceMeters, level, landmarks, sourceContext)
        }
    }
}

/** Food ordering and menu browsing. */
interface FoodOrderManager {
    fun openMenu(
        concession: String,
        menuUrl: String?,
        estimatedWaitMin: Double?,
        pickupSection: Int?,
        userSection: String?
    )

    companion object {
        lateinit var instance: FoodOrderManager
        fun openMenu(concession: String, menuUrl: String?, estimatedWaitMin: Double?, pickupSection: Int?, userSection: String?) {
            instance.openMenu(concession, menuUrl, estimatedWaitMin, pickupSection, userSection)
        }
    }
}

/** Info overlay that slides up from the bottom of the bubble. */
interface InfoOverlayManager {
    fun show(topic: String, body: String, deepLink: String?, bubbleLabel: String)

    companion object {
        lateinit var instance: InfoOverlayManager
        fun show(topic: String, body: String, deepLink: String?, bubbleLabel: String) {
            instance.show(topic, body, deepLink, bubbleLabel)
        }
    }
}

/** Accessibility service request and staff notification. */
interface AccessibilityServiceManager {
    fun request(service: String, location: String, notifyStaff: Boolean, userNeeds: List<String>)

    companion object {
        lateinit var instance: AccessibilityServiceManager
        fun request(service: String, location: String, notifyStaff: Boolean, userNeeds: List<String>) {
            instance.request(service, location, notifyStaff, userNeeds)
        }
    }
}

/** Fan engagement features: trivia, chants, selfie frames, replays, etc. */
interface EntertainmentManager {
    fun launch(contentType: String, title: String, url: String?)

    companion object {
        lateinit var instance: EntertainmentManager
        fun launch(contentType: String, title: String, url: String?) {
            instance.launch(contentType, title, url)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Usage Example (integration point in scan activity / fragment)
// ─────────────────────────────────────────────────────────────

/*
    // Called after the Gemma model returns its output string
    fun onModelOutput(rawJson: String, context: ScanContext) {
        val router = buildAgentRouter()
        try {
            val results = router.route(rawJson, context)
            for (result in results) {
                when (result) {
                    is AgentRouter.RouteResult.Dispatched -> {
                        log("Bubble ${result.bubbleIndex}: ${result.agent} → '${result.label}' dispatched")
                    }
                    is AgentRouter.RouteResult.ValidationError -> {
                        logError("Bubble ${result.bubbleIndex}: ${result.reason}")
                        // Fallback: show a generic "Something went wrong" bubble
                        showFallbackBubble(context)
                    }
                    is AgentRouter.RouteResult.UnknownAgent -> {
                        logError("Bubble ${result.bubbleIndex}: unknown agent '${result.agent}'")
                        showFallbackBubble(context)
                    }
                    is AgentRouter.RouteResult.HandlerRejected -> {
                        logError("Bubble ${result.bubbleIndex}: handler '${result.agent}' rejected payload")
                        showFallbackBubble(context)
                    }
                }
            }
        } catch (e: Exception) {
            logError("Router failure: ${e.message}")
            // Model output was not parseable at all — surface error in UI
            showGenericError()
        }
    }
*/
