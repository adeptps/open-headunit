package com.andrerinas.openheadunit.aap

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat

/** Values carried by the corrected six-field Android Auto message at NAV id 0x8004. */
data class AapModernNavigationTurn(
    val roadName: String?,
    val maneuverType: Int?,
    val turnDirection: Int?,
    val distanceMeters: Int?,
    val distanceUnit: Int?
)

internal sealed class AapNavigationTurnDecodeResult {
    data class Corrected(val turn: AapModernNavigationTurn) : AapNavigationTurnDecodeResult()
    object Legacy : AapNavigationTurnDecodeResult()
    object Ambiguous : AapNavigationTurnDecodeResult()
    object Invalid : AapNavigationTurnDecodeResult()
}

/**
 * Classifies 0x8004 without guessing across overlapping legacy and corrected field meanings.
 * Both layouts reuse tags 1-6, so packets in the overlap are deliberately reported as ambiguous.
 */
internal object AapModernNavigationTurnDecoder {
    fun decode(payload: ByteArray): AapModernNavigationTurn? =
        (classify(payload) as? AapNavigationTurnDecodeResult.Corrected)?.turn

    fun classify(payload: ByteArray): AapNavigationTurnDecodeResult {
        val input = CodedInputStream.newInstance(payload)
        var roadName: String? = null
        var rawManeuverType: Int? = null
        var rawTurnDirection: Int? = null
        var rawDistanceMeters: Int? = null
        var rawDistanceUnit: Int? = null

        try {
            while (!input.isAtEnd) {
                val tag = input.readTag()
                if (tag == 0) break
                when (WireFormat.getTagFieldNumber(tag)) {
                    1 -> if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                        roadName = input.readStringRequireUtf8().trim().takeIf { it.isNotEmpty() }
                    } else if (!input.skipField(tag)) break
                    2 -> if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_VARINT) {
                        rawManeuverType = input.readEnum()
                    } else if (!input.skipField(tag)) break
                    3 -> if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_VARINT) {
                        rawTurnDirection = input.readEnum()
                    } else if (!input.skipField(tag)) break
                    5 -> if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_VARINT) {
                        rawDistanceMeters = input.readInt32()
                    } else if (!input.skipField(tag)) break
                    6 -> if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_VARINT) {
                        rawDistanceUnit = input.readInt32()
                    } else if (!input.skipField(tag)) break
                    else -> if (!input.skipField(tag)) break
                }
            }
        } catch (_: Exception) {
            return AapNavigationTurnDecodeResult.Invalid
        }

        if (rawManeuverType?.let { it !in 0..MAX_MODERN_MANEUVER_TYPE } == true ||
            rawDistanceMeters?.let { it < 0 } == true
        ) {
            return AapNavigationTurnDecodeResult.Invalid
        }

        val correctedEvidence = rawManeuverType?.let { it > MAX_LEGACY_SIDE } == true
        val legacyEvidence =
            rawTurnDirection?.let { it !in 0..MAX_CORRECTED_DIRECTION } == true ||
                rawDistanceUnit?.let { it !in 0..MAX_CORRECTED_DISTANCE_UNIT } == true
        if (correctedEvidence && legacyEvidence) return AapNavigationTurnDecodeResult.Invalid
        if (legacyEvidence) return AapNavigationTurnDecodeResult.Legacy

        val correctedTurn = AapModernNavigationTurn(
            roadName = roadName,
            maneuverType = rawManeuverType,
            turnDirection = rawTurnDirection,
            distanceMeters = rawDistanceMeters,
            distanceUnit = rawDistanceUnit
        )
        if (correctedEvidence) {
            return AapNavigationTurnDecodeResult.Corrected(correctedTurn)
        }

        return AapNavigationTurnDecodeResult.Ambiguous
    }

    private const val MAX_MODERN_MANEUVER_TYPE = 50
    private const val MAX_LEGACY_SIDE = 3
    private const val MAX_CORRECTED_DIRECTION = 2
    private const val MAX_CORRECTED_DISTANCE_UNIT = 7
}
