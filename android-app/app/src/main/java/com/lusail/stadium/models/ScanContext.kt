package com.lusail.stadium.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level scan context carrying all data extracted from a QR/OCR scan
 * at Lusail Stadium. Serialized as JSON for the inference bridge.
 */
@Serializable
data class ScanContext(
    val scan: ScanData,
    val match: MatchData,
    val user: UserData,
    val locale: String = "en",
    @SerialName("device_id")
    val deviceId: String = "",
    @SerialName("scan_timestamp")
    val scanTimestamp: String = ""
)

/**
 * Data extracted from the physical scan.
 */
@Serializable
data class ScanData(
    /** Raw text or payload from the QR code / OCR */
    val payload: String,
    /** Type of the code: "qatar_qr", "url", "text", "product_barcode" */
    val type: String = "qatar_qr",
    /** Stadium zone / block number */
    val zone: String = "",
    /** Gate number */
    val gate: String = "",
    /** Section / seat info */
    val section: String = "",
    /** Raw confidence score from the scanner (0.0 - 1.0) */
    val confidence: Float = 1.0f
)

/**
 * Match / event information looked up from the stadium API.
 */
@Serializable
data class MatchData(
    @SerialName("match_id")
    val matchId: String = "",
    @SerialName("home_team")
    val homeTeam: String = "",
    @SerialName("away_team")
    val awayTeam: String = "",
    @SerialName("kickoff_time")
    val kickoffTime: String = "",
    /** Current match phase: "pre_match", "first_half", "half_time", "second_half", "post_match" */
    val phase: String = "pre_match",
    val venue: String = "Lusail Iconic Stadium",
    /** Visitor capacity fill (0.0 - 1.0) */
    val occupancy: Float = 0.72f
)

/**
 * User / ticket holder data.
 */
@Serializable
data class UserData(
    @SerialName("ticket_id")
    val ticketId: String = "",
    @SerialName("user_name")
    val userName: String = "Guest",
    @SerialName("ticket_tier")
    val ticketTier: String = "standard",
    /** Preferred language code */
    val language: String = "en",
    /** Accessibility flags */
    val accessibility: List<String> = emptyList()
)
