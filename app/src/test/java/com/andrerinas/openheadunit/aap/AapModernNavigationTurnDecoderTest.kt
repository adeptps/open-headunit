package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.google.protobuf.CodedOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayOutputStream

class AapModernNavigationTurnDecoderTest {
    @Test
    fun decode_readsCurrentSixFieldTurnLayout() {
        val bytes = protoBytes { output ->
            output.writeString(1, "Main Street")
            output.writeEnum(2, 8)
            output.writeEnum(3, 2)
            output.writeByteArray(4, byteArrayOf(1, 2, 3))
            output.writeInt32(5, 450)
            output.writeEnum(6, 1)
        }

        assertEquals(
            AapModernNavigationTurn(
                roadName = "Main Street",
                maneuverType = 8,
                turnDirection = 2,
                distanceMeters = 450,
                distanceUnit = 1
            ),
            AapModernNavigationTurnDecoder.decode(bytes)
        )
    }

    @Test
    fun decode_keepsAmbiguousOldLayoutOnLegacyParser() {
        val oldLayout = protoBytes { output ->
            output.writeString(1, "Old road")
            output.writeEnum(2, 2)
            output.writeEnum(3, 1)
        }

        assertNull(AapModernNavigationTurnDecoder.decode(oldLayout))
        val oldRoundaboutLayout = protoBytes { output ->
            output.writeString(1, "Old roundabout")
            output.writeEnum(2, 2)
            output.writeEnum(3, 13)
            output.writeByteArray(4, byteArrayOf(1, 2, 3))
            output.writeInt32(5, 5)
            output.writeInt32(6, 90)
        }
        assertNull(AapModernNavigationTurnDecoder.decode(oldRoundaboutLayout))
        assertNull(AapModernNavigationTurnDecoder.decode(byteArrayOf(0x0a, 0x7f)))
    }

    @Test
    fun classify_dropsOverlappingCorrectedLayoutInsteadOfCorruptingItAsLegacy() {
        val correctedButAmbiguous = protoBytes { output ->
            output.writeString(1, "Main Street")
            output.writeEnum(2, 2)
            output.writeEnum(3, 1)
            output.writeByteArray(4, byteArrayOf(1, 2, 3))
            output.writeInt32(5, 450)
            output.writeInt32(6, 1)
        }

        assertSame(
            AapNavigationTurnDecodeResult.Ambiguous,
            AapModernNavigationTurnDecoder.classify(correctedButAmbiguous)
        )
        assertNull(AapModernNavigationTurnDecoder.decode(correctedButAmbiguous))

        val correctedWithoutDistance = protoBytes { output ->
            output.writeString(1, "Main Street")
            output.writeEnum(2, 3)
            output.writeEnum(3, 1)
        }
        assertSame(
            AapNavigationTurnDecodeResult.Ambiguous,
            AapModernNavigationTurnDecoder.classify(correctedWithoutDistance)
        )
    }

    @Test
    fun classify_rejectsPayloadWithMutuallyExclusiveLayoutEvidence() {
        val conflicting = protoBytes { output ->
            output.writeString(1, "Main Street")
            output.writeEnum(2, 8)
            output.writeEnum(3, 13)
            output.writeInt32(5, 450)
            output.writeInt32(6, 90)
        }

        assertSame(
            AapNavigationTurnDecodeResult.Invalid,
            AapModernNavigationTurnDecoder.classify(conflicting)
        )
        assertNull(AapModernNavigationTurnDecoder.decode(conflicting))
    }

    @Test
    fun mapper_acceptsNewerManeuverValues() {
        assertEquals("in_circular_movement", AapMviteNavigationMapper.maneuverSign(43))
        assertEquals("out_circular_movement", AapMviteNavigationMapper.maneuverSign(46))
        assertEquals("boardferry", AapMviteNavigationMapper.maneuverSign(50))
        assertNull(AapMviteNavigationMapper.maneuverSign(99))

        val maneuverWithUnknownEnum = NavigationStatus.NavigationManeuver.parseFrom(
            protoBytes { it.writeEnum(1, 43) }
        )
        val snapshot = AapNavigationHelper.NavigationSnapshot(
            navigationState = AapNavigationHelper.TimedMessage(
                NavigationStatus.NavigationState.newBuilder()
                    .addSteps(
                        NavigationStatus.NavigationStep.newBuilder()
                            .setManeuver(maneuverWithUnknownEnum)
                            .build()
                    )
                    .build(),
                1L
            )
        )
        assertEquals(
            "in_circular_movement",
            Regex("\\\"sign\\\":\\\"([^\\\"]+)\\\"")
                .find(AapMviteNavigationMapper.map(snapshot).first().toJson())
                ?.groupValues
                ?.get(1)
        )
    }

    private fun protoBytes(write: (CodedOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = CodedOutputStream.newInstance(bytes)
        write(output)
        output.flush()
        return bytes.toByteArray()
    }
}
