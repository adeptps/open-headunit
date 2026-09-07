package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationLane.LaneDirection.Shape
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationManeuver.NavigationType
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail.NextEvent as LegacyNextEvent
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail.Side as LegacySide

/** A single MVite-compatible event with deterministic JSON encoding. */
class AapMviteNavigationEvent internal constructor(
    val event: String,
    private val encoded: String,
    internal val routeActiveValue: Boolean? = null,
    internal val routeActivityEvidence: Boolean = true
) {
    fun toJson(): String = encoded

    override fun toString(): String = encoded
}

/**
 * Lossless mapping of navigation values that Android Auto actually exposes to the MVite event
 * vocabulary consumed by SCS. Values such as speed limits, cameras, traffic or route geometry are
 * deliberately not synthesized because they are not present on the Android Auto navigation channel.
 */
object AapMviteNavigationMapper {
    private const val SIGN_FORWARD = "forward"
    private const val SIGN_TAKE_LEFT = "take_left"
    private const val SIGN_TAKE_RIGHT = "take_right"
    private const val SIGN_TURN_LEFT = "turn_left"
    private const val SIGN_TURN_RIGHT = "turn_right"
    private const val SIGN_HARD_TURN_LEFT = "hard_turn_left"
    private const val SIGN_HARD_TURN_RIGHT = "hard_turn_right"
    private const val SIGN_TURN_BACK_LEFT = "turn_back_left"
    private const val SIGN_TURN_BACK_RIGHT = "turn_back_right"
    private const val SIGN_IN_ROUNDABOUT = "in_circular_movement"
    private const val SIGN_OUT_ROUNDABOUT = "out_circular_movement"
    private const val SIGN_FERRY = "boardferry"
    private const val SIGN_FINISH = "finish"
    private const val SIGN_EXIT_LEFT = "exit_left"
    private const val SIGN_EXIT_RIGHT = "exit_right"

    /** Maps the latest coherent Android Auto navigation snapshot to independent MVite events. */
    @Suppress("DEPRECATION")
    fun map(snapshot: AapNavigationHelper.NavigationSnapshot): List<AapMviteNavigationEvent> {
        val events = mutableListOf<AapMviteNavigationEvent>()
        val state = snapshot.navigationState?.payload
        val currentPosition = snapshot.currentPosition?.payload
        val flatTurn = snapshot.modernTurn?.payload
        val firstStep = state?.stepsList?.firstOrNull()

        snapshot.clusterStatus?.payload?.status?.let(::routeActiveValue)?.let { active ->
            events += routeActive(active)
        }

        val stepDistance = currentPosition
            ?.takeIf { it.hasStepDistance() && it.stepDistance.hasDistance() }
            ?.stepDistance
            ?.distance
        val stepDistanceMeters = stepDistance
            ?.takeIf { it.hasMeters() }
            ?.meters
            ?.takeIf { it >= 0 }
            ?: flatTurn?.distanceMeters
        val maneuverDistance = stepDistance?.let(::mviteDistance)
            ?: stepDistanceMeters?.let { MviteDistance(it, "м") }

        val modernManeuver = firstStep
            ?.takeIf { it.hasManeuver() }
            ?.maneuver
        val modernManeuverType = modernManeuver?.let { maneuver ->
            if (maneuver.hasType()) {
                maneuver.type.number
            } else {
                maneuver.unknownFields.getField(1).varintList.firstOrNull()?.toInt()
            }
        }
        val legacyDetail = snapshot.nextTurnDetail?.payload
        val maneuverSign = if (modernManeuver != null) {
            modernManeuver.let { maneuver ->
                if (maneuver.hasRoundaboutExitNumber() && maneuver.roundaboutExitNumber > 0) {
                    SIGN_IN_ROUNDABOUT
                } else {
                    modernManeuverType?.let(::maneuverSign)
                }
            }
        } else if (flatTurn?.maneuverType != null) {
            maneuverSign(flatTurn.maneuverType)
        } else {
            legacyDetail
                ?.takeIf { it.hasNextTurn() }
                ?.let { detail ->
                    if (detail.hasTurnNumber() && detail.turnNumber > 0) {
                        SIGN_IN_ROUNDABOUT
                    } else {
                        legacyManeuverSign(detail)
                    }
                }
        }
        val nextRoad = firstStep
            ?.takeIf { it.hasRoadInfo() }
            ?.roadInfo
            ?.roadNamesList
            ?.firstNotNullOfOrNull { name -> name.trim().takeIf { it.isNotEmpty() } }
            ?: flatTurn?.roadName
            ?: legacyDetail
                ?.road
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        if (modernManeuver != null || flatTurn != null || legacyDetail != null ||
            maneuverDistance != null || nextRoad != null
        ) {
            events += event(
                "Maneuver",
                "distance" to (maneuverDistance?.value?.let(::jsonNumber) ?: JsonNull),
                "metric" to (maneuverDistance?.metric?.let(::jsonString) ?: JsonNull),
                "nextRoad" to (nextRoad?.let(::jsonString) ?: JsonNull),
                "sign" to (maneuverSign?.let(::jsonString) ?: JsonNull)
            )
        }

        val exitNumber = when {
            modernManeuver != null -> modernManeuver
                .takeIf { it.hasRoundaboutExitNumber() }
                ?.roundaboutExitNumber
                ?.takeIf { it > 0 }
            flatTurn != null -> null
            else -> legacyDetail
                ?.takeIf { it.hasTurnNumber() }
                ?.turnNumber
                ?.takeIf { it > 0 }
        }
        if (state != null || modernManeuver != null || flatTurn?.maneuverType != null ||
            legacyDetail?.hasNextTurn() == true
        ) {
            events += exitNumber(exitNumber)
        }

        val currentRoadStatus = currentPosition
            ?.takeIf { it.hasCurrentRoad() && it.currentRoad.hasName() }
            ?.currentRoad
            ?.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val cueStatus = firstStep
            ?.takeIf { it.hasInstruction() && it.instruction.hasText() }
            ?.instruction
            ?.text
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val routeStatus = currentRoadStatus ?: cueStatus
        if (routeStatus != null) {
            events += event(
                "RouteStatus",
                "value" to jsonString(routeStatus)
            )
        }

        val destinationDistance = currentPosition?.destinationDistancesList?.firstOrNull()
        val distanceLeft = destinationDistance
            ?.takeIf { it.hasDistance() }
            ?.distance
            ?.let(::mviteDistance)
        if (distanceLeft != null) {
            events += event(
                "Eta.DistanceLeft",
                "value" to jsonNumber(distanceLeft.value),
                "metric" to jsonString(distanceLeft.metric)
            )
        }

        val timeLeftSeconds = destinationDistance
            ?.takeIf { it.hasTimeToArrivalSeconds() }
            ?.timeToArrivalSeconds
            ?.takeIf { it >= 0L }
        if (timeLeftSeconds != null) {
            val totalMinutes = timeLeftSeconds / 60L
            val day = totalMinutes / (24L * 60L)
            val hour = (totalMinutes / 60L) % 24L
            val minute = totalMinutes % 60L
            events += event(
                "Eta.TimeLeft",
                "day" to (day.takeIf { it > 0 }?.let(::jsonNumber) ?: JsonNull),
                "hour" to (hour.takeIf { it > 0 }?.let(::jsonNumber) ?: JsonNull),
                "minute" to (minute.takeIf { it > 0 }?.let(::jsonNumber) ?: JsonNull),
                "value" to jsonString(formatTimeLeft(day, hour, minute))
            )
        }

        val arrivalTime = destinationDistance
            ?.takeIf { it.hasEstimatedTimeAtArrival() }
            ?.estimatedTimeAtArrival
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (arrivalTime != null) {
            val parsedTime = parseArrivalTime(arrivalTime)
            if (parsedTime != null) {
                events += event(
                    "Eta.ArrivalTime",
                    "hour" to jsonNumber(parsedTime.first),
                    "minute" to jsonNumber(parsedTime.second)
                )
            }
        }

        val laneItems = firstStep
            ?.takeIf {
                stepDistanceMeters != null && stepDistanceMeters <= MAX_MVITE_LANE_DISTANCE_METERS
            }
            ?.let { step ->
                val laneDistanceMeters = requireNotNull(stepDistanceMeters)
                val lanes = step.lanesList.mapNotNull { lane ->
                    val layers = lane.laneDirectionsList.mapIndexedNotNull direction@ { directionIndex, direction ->
                        if (!direction.hasShape()) return@direction null
                        val shape = laneShape(direction.shape) ?: return@direction null
                        jsonObject(
                            "type" to jsonString(shape),
                            "view" to jsonString(if (directionIndex == 0) "large" else "small"),
                            "isHighlighted" to direction
                                .takeIf { it.hasIsHighlighted() && it.isHighlighted }
                                ?.let { jsonBoolean(true) }
                        )
                    }
                    layers.takeIf { it.isNotEmpty() }?.let(::jsonArray)
                }
                if (lanes.isEmpty()) emptyList() else listOf(
                    jsonObject(
                        "idx" to jsonNumber(0),
                        "dist" to jsonNumber(laneDistanceMeters),
                        "lanes" to jsonArray(lanes)
                    )
                )
            }
            .orEmpty()
        if (state != null) {
            events += event(
                "Lanes",
                "items" to jsonArray(laneItems),
                routeActivityEvidence = laneItems.isNotEmpty()
            )
        }

        return events
    }

    /** Creates the lifecycle event used for explicit start/stop messages outside a snapshot. */
    fun routeActive(active: Boolean): AapMviteNavigationEvent =
        event("RouteActive", "value" to jsonBoolean(active), routeActiveValue = active)

    internal fun lanesEmpty(): AapMviteNavigationEvent =
        event(
            "Lanes",
            "items" to jsonArray(emptyList()),
            routeActivityEvidence = false
        )

    internal fun exitNumber(value: Int?): AapMviteNavigationEvent =
        event(
            "Maneuver.ExitNumber",
            "value" to (value?.let(::jsonNumber) ?: JsonNull),
            routeActivityEvidence = value != null
        )

    /** Creates the MVite speed event from a real location fix, in kilometres per hour. */
    fun speed(speedKph: Double): AapMviteNavigationEvent {
        val rounded = kotlin.math.round(speedKph * 10.0) / 10.0
        val value: Number = if (rounded % 1.0 == 0.0) rounded.toInt() else rounded
        return event("Speed", "value" to jsonNumber(value))
    }

    internal fun maneuverSign(type: NavigationType): String? = when (type) {
        NavigationType.UNKNOWN -> null

        NavigationType.DEPART,
        NavigationType.NAME_CHANGE,
        NavigationType.STRAIGHT -> SIGN_FORWARD

        NavigationType.KEEP_LEFT,
        NavigationType.TURN_SLIGHT_LEFT,
        NavigationType.ON_RAMP_SLIGHT_LEFT,
        NavigationType.FORK_LEFT,
        NavigationType.MERGE_LEFT -> SIGN_TAKE_LEFT

        NavigationType.KEEP_RIGHT,
        NavigationType.TURN_SLIGHT_RIGHT,
        NavigationType.ON_RAMP_SLIGHT_RIGHT,
        NavigationType.FORK_RIGHT,
        NavigationType.MERGE_RIGHT -> SIGN_TAKE_RIGHT

        NavigationType.TURN_NORMAL_LEFT,
        NavigationType.ON_RAMP_NORMAL_LEFT -> SIGN_TURN_LEFT

        NavigationType.TURN_NORMAL_RIGHT,
        NavigationType.ON_RAMP_NORMAL_RIGHT -> SIGN_TURN_RIGHT

        NavigationType.TURN_SHARP_LEFT,
        NavigationType.ON_RAMP_SHARP_LEFT -> SIGN_HARD_TURN_LEFT

        NavigationType.TURN_SHARP_RIGHT,
        NavigationType.ON_RAMP_SHARP_RIGHT -> SIGN_HARD_TURN_RIGHT

        NavigationType.U_TURN_LEFT,
        NavigationType.ON_RAMP_U_TURN_LEFT -> SIGN_TURN_BACK_LEFT

        NavigationType.U_TURN_RIGHT,
        NavigationType.ON_RAMP_U_TURN_RIGHT -> SIGN_TURN_BACK_RIGHT

        NavigationType.OFF_RAMP_SLIGHT_LEFT,
        NavigationType.OFF_RAMP_NORMAL_LEFT -> SIGN_EXIT_LEFT

        NavigationType.OFF_RAMP_SLIGHT_RIGHT,
        NavigationType.OFF_RAMP_NORMAL_RIGHT -> SIGN_EXIT_RIGHT

        NavigationType.MERGE_SIDE_UNSPECIFIED -> SIGN_FORWARD
        NavigationType.ROUNDABOUT_ENTER -> SIGN_IN_ROUNDABOUT
        NavigationType.ROUNDABOUT_ENTER_CW,
        NavigationType.ROUNDABOUT_ENTER_CCW -> SIGN_IN_ROUNDABOUT

        NavigationType.ROUNDABOUT_EXIT,
        NavigationType.ROUNDABOUT_EXIT_CW,
        NavigationType.ROUNDABOUT_EXIT_CCW,
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE -> SIGN_OUT_ROUNDABOUT

        NavigationType.FERRY_BOAT,
        NavigationType.FERRY_TRAIN,
        NavigationType.FERRY_BOAT_LEFT,
        NavigationType.FERRY_BOAT_RIGHT,
        NavigationType.FERRY_TRAIN_LEFT,
        NavigationType.FERRY_TRAIN_RIGHT -> SIGN_FERRY

        NavigationType.DESTINATION,
        NavigationType.DESTINATION_STRAIGHT,
        NavigationType.DESTINATION_LEFT,
        NavigationType.DESTINATION_RIGHT -> SIGN_FINISH

        else -> null
    }

    internal fun maneuverSign(type: Int): String? = when (type) {
        0 -> null
        1, 2, 36 -> SIGN_FORWARD
        3, 5, 13, 25, 27 -> SIGN_TAKE_LEFT
        4, 6, 14, 26, 28 -> SIGN_TAKE_RIGHT
        7, 15 -> SIGN_TURN_LEFT
        8, 16 -> SIGN_TURN_RIGHT
        9, 17 -> SIGN_HARD_TURN_LEFT
        10, 18 -> SIGN_HARD_TURN_RIGHT
        11, 19 -> SIGN_TURN_BACK_LEFT
        12, 20 -> SIGN_TURN_BACK_RIGHT
        21, 23 -> SIGN_EXIT_LEFT
        22, 24 -> SIGN_EXIT_RIGHT
        29 -> SIGN_FORWARD
        30, 43, 45 -> SIGN_IN_ROUNDABOUT
        31, 32, 33, 34, 35, 44, 46 -> SIGN_OUT_ROUNDABOUT
        37, 38, 47, 48, 49, 50 -> SIGN_FERRY
        39, 40, 41, 42 -> SIGN_FINISH
        else -> null
    }

    private fun routeActiveValue(
        status: NavigationStatus.NavigationClusterStatus.NavigationStatusEnum
    ): Boolean? = when (status) {
        NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.ACTIVE,
        NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.REROUTING -> true

        NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.INACTIVE,
        NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.UNAVAILABLE -> false

        else -> null
    }

    private fun legacyManeuverSign(detail: NavigationStatus.NextTurnDetail): String? {
        val side = detail.side.takeIf { detail.hasSide() }
        return when (detail.nextTurn) {
            LegacyNextEvent.UNKNOWN -> null

            LegacyNextEvent.DEPART,
            LegacyNextEvent.NAME_CHANGE,
            LegacyNextEvent.STRAIGHT -> SIGN_FORWARD

            LegacyNextEvent.SLIGHT_TURN,
            LegacyNextEvent.ON_RAMP,
            LegacyNextEvent.FORK,
            LegacyNextEvent.MERGE -> sideSign(side, SIGN_TAKE_LEFT, SIGN_TAKE_RIGHT)

            LegacyNextEvent.TURN -> sideSign(side, SIGN_TURN_LEFT, SIGN_TURN_RIGHT)
            LegacyNextEvent.SHARP_TURN -> sideSign(side, SIGN_HARD_TURN_LEFT, SIGN_HARD_TURN_RIGHT)
            LegacyNextEvent.U_TURN -> sideSign(side, SIGN_TURN_BACK_LEFT, SIGN_TURN_BACK_RIGHT)
            LegacyNextEvent.OFFRAMP -> sideSign(side, SIGN_EXIT_LEFT, SIGN_EXIT_RIGHT)
            LegacyNextEvent.ROUNDABOUT_ENTER -> SIGN_IN_ROUNDABOUT
            LegacyNextEvent.ROUNDABOUT_EXIT,
            LegacyNextEvent.ROUNDABOUT_ENTER_AND_EXIT -> SIGN_OUT_ROUNDABOUT

            LegacyNextEvent.FERRY_BOAT,
            LegacyNextEvent.FERRY_TRAIN -> SIGN_FERRY

            LegacyNextEvent.DESTINATION -> SIGN_FINISH
            else -> null
        }
    }

    private fun sideSign(side: LegacySide?, left: String, right: String): String? = when (side) {
        LegacySide.LEFT -> left
        LegacySide.RIGHT -> right
        else -> null
    }

    private fun laneShape(shape: Shape): String? = when (shape) {
        Shape.UNKNOWN -> null
        Shape.STRAIGHT -> "straightahead"
        Shape.SLIGHT_LEFT -> "left45"
        Shape.SLIGHT_RIGHT -> "right45"
        Shape.NORMAL_LEFT -> "left90"
        Shape.NORMAL_RIGHT -> "right90"
        Shape.SHARP_LEFT -> "left135"
        Shape.SHARP_RIGHT -> "right135"
        Shape.U_TURN_LEFT -> "left180"
        Shape.U_TURN_RIGHT -> "right180"
        else -> null
    }

    private fun formatTimeLeft(day: Long, hour: Long, minute: Long): String {
        val parts = mutableListOf<String>()
        if (day > 0) parts += "$day дн."
        if (hour > 0) parts += "$hour ч."
        if (minute > 0) parts += "$minute мин"
        return parts.joinToString(" ").ifBlank { "скоро" }
    }

    private fun parseArrivalTime(value: String): Pair<Int, Int>? {
        val match = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").matchEntire(value) ?: return null
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun mviteDistance(
        distance: NavigationStatus.NavigationDistance
    ): MviteDistance? {
        val meters = distance.takeIf { it.hasMeters() }?.meters?.takeIf { it >= 0 }
        val metric = distance.takeIf { it.hasDisplayUnits() }?.displayUnits?.let { units ->
            when (units) {
                NavigationStatus.NavigationDistance.DistanceUnits.METERS -> "м"
                NavigationStatus.NavigationDistance.DistanceUnits.KILOMETERS,
                NavigationStatus.NavigationDistance.DistanceUnits.KILOMETERS_P1 -> "км"
                else -> null
            }
        }
        val displayed = distance
            .takeIf { metric != null && it.hasDisplayValue() }
            ?.displayValue
            ?.trim()
            ?.replace(',', '.')
            ?.takeIf { DISPLAY_NUMBER.matches(it) }
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0.0 && it.isFinite() }
            ?.let { if (it % 1.0 == 0.0) it.toInt() else it }
        return if (displayed != null && metric != null) {
            MviteDistance(displayed, metric)
        } else {
            meters?.let { MviteDistance(it, "м") }
        }
    }

    private fun event(
        name: String,
        vararg fields: Pair<String, JsonValue?>,
        routeActiveValue: Boolean? = null,
        routeActivityEvidence: Boolean = true
    ): AapMviteNavigationEvent {
        val root = jsonObject("event" to jsonString(name), *fields)
        return AapMviteNavigationEvent(
            name,
            root.render(),
            routeActiveValue,
            routeActivityEvidence
        )
    }

    private fun jsonObject(vararg fields: Pair<String, JsonValue?>): JsonObject =
        JsonObject(fields.mapNotNull { (name, value) -> value?.let { name to it } })

    private fun jsonArray(values: List<JsonValue>): JsonArray = JsonArray(values)

    private fun jsonString(value: String): JsonString = JsonString(value)

    private fun jsonNumber(value: Number): JsonNumber = JsonNumber(value)

    private fun jsonBoolean(value: Boolean): JsonBoolean = JsonBoolean(value)

    private sealed class JsonValue {
        fun render(): String = buildString { appendTo(this) }

        abstract fun appendTo(target: StringBuilder)
    }

    private class JsonObject(
        private val fields: List<Pair<String, JsonValue>>
    ) : JsonValue() {
        override fun appendTo(target: StringBuilder) {
            target.append('{')
            fields.forEachIndexed { index, (name, value) ->
                if (index > 0) target.append(',')
                JsonString(name).appendTo(target)
                target.append(':')
                value.appendTo(target)
            }
            target.append('}')
        }
    }

    private class JsonArray(
        private val values: List<JsonValue>
    ) : JsonValue() {
        override fun appendTo(target: StringBuilder) {
            target.append('[')
            values.forEachIndexed { index, value ->
                if (index > 0) target.append(',')
                value.appendTo(target)
            }
            target.append(']')
        }
    }

    private class JsonString(
        private val value: String
    ) : JsonValue() {
        override fun appendTo(target: StringBuilder) {
            target.append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> target.append("\\\"")
                    '\\' -> target.append("\\\\")
                    '\b' -> target.append("\\b")
                    '\u000C' -> target.append("\\f")
                    '\n' -> target.append("\\n")
                    '\r' -> target.append("\\r")
                    '\t' -> target.append("\\t")
                    else -> if (char.code < 0x20) {
                        target.append("\\u")
                        target.append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        target.append(char)
                    }
                }
            }
            target.append('"')
        }
    }

    private class JsonNumber(
        private val value: Number
    ) : JsonValue() {
        override fun appendTo(target: StringBuilder) {
            target.append(value.toString())
        }
    }

    private class JsonBoolean(
        private val value: Boolean
    ) : JsonValue() {
        override fun appendTo(target: StringBuilder) {
            target.append(if (value) "true" else "false")
        }
    }

    private object JsonNull : JsonValue() {
        override fun appendTo(target: StringBuilder) {
            target.append("null")
        }
    }

    private data class MviteDistance(val value: Number, val metric: String)

    private const val MAX_MVITE_LANE_DISTANCE_METERS = 1000
    private val DISPLAY_NUMBER = Regex("^\\d+(?:[.,]\\d+)?$")
}
