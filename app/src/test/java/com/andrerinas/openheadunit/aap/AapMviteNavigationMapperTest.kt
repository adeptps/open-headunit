package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationLane.LaneDirection.Shape
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationManeuver.NavigationType
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AapMviteNavigationMapperTest {
    @Test
    fun map_emitsEveryAvailableMviteNavigationEvent() {
        val firstLane = NavigationStatus.NavigationLane.newBuilder()
            .addLaneDirections(
                NavigationStatus.NavigationLane.LaneDirection.newBuilder()
                    .setShape(Shape.SLIGHT_LEFT)
                    .setIsHighlighted(false)
                    .build()
            )
            .addLaneDirections(
                NavigationStatus.NavigationLane.LaneDirection.newBuilder()
                    .setShape(Shape.NORMAL_LEFT)
                    .setIsHighlighted(true)
                    .build()
            )
            .build()
        val secondLane = NavigationStatus.NavigationLane.newBuilder()
            .addLaneDirections(
                NavigationStatus.NavigationLane.LaneDirection.newBuilder()
                    .setShape(Shape.STRAIGHT)
                    .build()
            )
            .build()
        val step = NavigationStatus.NavigationStep.newBuilder()
            .setManeuver(
                NavigationStatus.NavigationManeuver.newBuilder()
                    .setType(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE)
                    .setRoundaboutExitNumber(3)
                    .build()
            )
            .setRoadInfo(
                NavigationStatus.NavigationRoadInfo.newBuilder()
                    .addRoadNames("пр-т \"Мира\"\nсевер\\путь")
                    .build()
            )
            .setInstruction(
                NavigationStatus.NavigationText.newBuilder()
                    .setText("  Пробка 2 км ")
                    .build()
            )
            .addLanes(firstLane)
            .addLanes(secondLane)
            .build()
        val snapshot = AapNavigationHelper.NavigationSnapshot(
            clusterStatus = timed(
                NavigationStatus.NavigationClusterStatus.newBuilder()
                    .setStatus(NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.REROUTING)
                    .build()
            ),
            navigationState = timed(
                NavigationStatus.NavigationState.newBuilder()
                    .addSteps(step)
                    .build()
            ),
            currentPosition = timed(
                NavigationStatus.NavigationCurrentPosition.newBuilder()
                    .setStepDistance(
                        NavigationStatus.NavigationStepDistance.newBuilder()
                            .setDistance(distance(350))
                            .build()
                    )
                    .addDestinationDistances(
                        NavigationStatus.NavigationDestinationDistance.newBuilder()
                            .setDistance(distance(12_345))
                            .setTimeToArrivalSeconds(90_061L)
                            .setEstimatedTimeAtArrival("14:35")
                            .build()
                    )
                    .setCurrentRoad(
                        NavigationStatus.NavigationRoad.newBuilder()
                            .setName("Большая Дорогомиловская ул.")
                            .build()
                    )
                    .build()
            )
        )

        val events = AapMviteNavigationMapper.map(snapshot)

        assertEquals(
            listOf(
                "RouteActive",
                "Maneuver",
                "Maneuver.ExitNumber",
                "RouteStatus",
                "Eta.DistanceLeft",
                "Eta.TimeLeft",
                "Eta.ArrivalTime",
                "Lanes"
            ),
            events.map { it.event }
        )
        assertEquals(
            "{\"event\":\"RouteActive\",\"value\":true}",
            events[0].toJson()
        )
        assertEquals(
            "{\"event\":\"Maneuver\",\"distance\":350,\"metric\":\"м\",\"nextRoad\":\"пр-т \\\"Мира\\\"\\nсевер\\\\путь\",\"sign\":\"in_circular_movement\"}",
            events[1].toJson()
        )
        assertEquals(
            "{\"event\":\"Maneuver.ExitNumber\",\"value\":3}",
            events[2].toJson()
        )
        assertEquals(
            "{\"event\":\"RouteStatus\",\"value\":\"Большая Дорогомиловская ул.\"}",
            events[3].toJson()
        )
        assertEquals(
            "{\"event\":\"Eta.DistanceLeft\",\"value\":12345,\"metric\":\"м\"}",
            events[4].toJson()
        )
        assertEquals(
            "{\"event\":\"Eta.TimeLeft\",\"day\":1,\"hour\":1,\"minute\":1,\"value\":\"1 дн. 1 ч. 1 мин\"}",
            events[5].toJson()
        )
        assertEquals(
            "{\"event\":\"Eta.ArrivalTime\",\"hour\":14,\"minute\":35}",
            events[6].toJson()
        )
        assertEquals(
            "{\"event\":\"Lanes\",\"items\":[{\"idx\":0,\"dist\":350,\"lanes\":[[{\"type\":\"left45\",\"view\":\"large\"},{\"type\":\"left90\",\"view\":\"small\",\"isHighlighted\":true}],[{\"type\":\"straightahead\",\"view\":\"large\"}]]}]}",
            events[7].toJson()
        )
    }

    @Test
    fun maneuverSign_mapsAll51AndroidAutoTypesToTheSCSCatalog() {
        val expectedByWireNumber = listOf<String?>(
            null,
            "forward",
            "forward",
            "take_left",
            "take_right",
            "take_left",
            "take_right",
            "turn_left",
            "turn_right",
            "hard_turn_left",
            "hard_turn_right",
            "turn_back_left",
            "turn_back_right",
            "take_left",
            "take_right",
            "turn_left",
            "turn_right",
            "hard_turn_left",
            "hard_turn_right",
            "turn_back_left",
            "turn_back_right",
            "exit_left",
            "exit_right",
            "exit_left",
            "exit_right",
            "take_left",
            "take_right",
            "take_left",
            "take_right",
            "forward",
            "in_circular_movement",
            "out_circular_movement",
            "out_circular_movement",
            "out_circular_movement",
            "out_circular_movement",
            "out_circular_movement",
            "forward",
            "boardferry",
            "boardferry",
            "finish",
            "finish",
            "finish",
            "finish",
            "in_circular_movement",
            "out_circular_movement",
            "in_circular_movement",
            "out_circular_movement",
            "boardferry",
            "boardferry",
            "boardferry",
            "boardferry"
        )
        val allowedSigns = setOf(
            "forward",
            "take_left",
            "take_right",
            "turn_left",
            "turn_right",
            "hard_turn_left",
            "hard_turn_right",
            "turn_back_left",
            "turn_back_right",
            "in_circular_movement",
            "out_circular_movement",
            "boardferry",
            "finish",
            "exit_left",
            "exit_right"
        )

        assertEquals(51, NavigationType.values().size)
        assertEquals(51, expectedByWireNumber.size)
        expectedByWireNumber.forEachIndexed { wireNumber, expected ->
            assertEquals(
                "wire type $wireNumber",
                expected,
                AapMviteNavigationMapper.maneuverSign(NavigationType.forNumber(wireNumber))
            )
        }
        assertEquals(allowedSigns, expectedByWireNumber.filterNotNull().toSet())
    }

    @Test
    fun map_omitsValuesThatAreNotPresentOnTheAndroidAutoChannel() {
        val snapshot = AapNavigationHelper.NavigationSnapshot(
            navigationState = timed(
                NavigationStatus.NavigationState.newBuilder()
                    .addSteps(
                        NavigationStatus.NavigationStep.newBuilder()
                            .setManeuver(
                                NavigationStatus.NavigationManeuver.newBuilder()
                                    .setType(NavigationType.DESTINATION)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            ),
            currentPosition = timed(
                NavigationStatus.NavigationCurrentPosition.newBuilder()
                    .setCurrentRoad(
                        NavigationStatus.NavigationRoad.newBuilder()
                            .setName("Current road is not the next road")
                            .build()
                    )
                    .build()
            )
        )

        val events = AapMviteNavigationMapper.map(snapshot)

        assertEquals(
            listOf("Maneuver", "Maneuver.ExitNumber", "RouteStatus", "Lanes"),
            events.map { it.event }
        )
        assertEquals(
            "{\"event\":\"Maneuver\",\"distance\":null,\"metric\":null,\"nextRoad\":null,\"sign\":\"finish\"}",
            events[0].toJson()
        )
        assertEquals("{\"event\":\"Maneuver.ExitNumber\",\"value\":null}", events[1].toJson())
        assertEquals(
            "{\"event\":\"RouteStatus\",\"value\":\"Current road is not the next road\"}",
            events[2].toJson()
        )
        assertEquals("{\"event\":\"Lanes\",\"items\":[]}", events[3].toJson())
        assertTrue(AapMviteNavigationMapper.map(AapNavigationHelper.NavigationSnapshot()).isEmpty())

        val unknown = AapNavigationHelper.NavigationSnapshot(
            navigationState = timed(
                NavigationStatus.NavigationState.newBuilder()
                    .addSteps(
                        NavigationStatus.NavigationStep.newBuilder()
                            .setManeuver(
                                NavigationStatus.NavigationManeuver.newBuilder()
                                    .setType(NavigationType.UNKNOWN)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
        )
        assertEquals(
            listOf(
                "{\"event\":\"Maneuver\",\"distance\":null,\"metric\":null,\"nextRoad\":null,\"sign\":null}",
                "{\"event\":\"Maneuver.ExitNumber\",\"value\":null}",
                "{\"event\":\"Lanes\",\"items\":[]}"
            ),
            AapMviteNavigationMapper.map(unknown).map { it.toJson() }
        )
    }

    @Test
    fun arrivalTime_omitsNonCanonicalTextInsteadOfChangingTheMviteShape() {
        val snapshot = AapNavigationHelper.NavigationSnapshot(
            currentPosition = timed(
                NavigationStatus.NavigationCurrentPosition.newBuilder()
                    .addDestinationDistances(
                        NavigationStatus.NavigationDestinationDistance.newBuilder()
                            .setEstimatedTimeAtArrival("tomorrow morning")
                            .build()
                    )
                    .build()
            )
        )

        assertTrue(AapMviteNavigationMapper.map(snapshot).isEmpty())
    }

    @Test
    fun map_pairsLegacyRoundaboutExitNumberAndIgnoresAmbiguous0x8005DistanceFields() {
        @Suppress("DEPRECATION")
        val snapshot = AapNavigationHelper.NavigationSnapshot(
            nextTurnDetail = timed(
                NavigationStatus.NextTurnDetail.newBuilder()
                    .setRoad("Exit 12")
                    .setNextTurn(NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT)
                    .setTurnNumber(2)
                    .build()
            ),
            nextTurnDistance = timed(
                NavigationStatus.NextTurnDistanceEvent.newBuilder()
                    .setDistanceMeters(420)
                    .build()
            )
        )

        val events = AapMviteNavigationMapper.map(snapshot)

        assertEquals(listOf("Maneuver", "Maneuver.ExitNumber"), events.map { it.event })
        assertEquals(
            "{\"event\":\"Maneuver\",\"distance\":null,\"metric\":null,\"nextRoad\":\"Exit 12\",\"sign\":\"in_circular_movement\"}",
            events[0].toJson()
        )
        assertEquals(
            "{\"event\":\"Maneuver.ExitNumber\",\"value\":2}",
            events[1].toJson()
        )
    }

    @Test
    fun map_doesNotInventForwardSignForDirectionlessLegacyTurn() {
        val snapshot = AapNavigationHelper.NavigationSnapshot(
            nextTurnDetail = timed(
                NavigationStatus.NextTurnDetail.newBuilder()
                    .setRoad("Main Street")
                    .setNextTurn(NavigationStatus.NextTurnDetail.NextEvent.TURN)
                    .build()
            )
        )

        assertEquals(
            "{\"event\":\"Maneuver\",\"distance\":null,\"metric\":null,\"nextRoad\":\"Main Street\",\"sign\":null}",
            AapMviteNavigationMapper.map(snapshot).first().toJson()
        )
    }

    @Test
    fun map_clearsExitNumberWhenAuthoritativeStateDropsManeuver() {
        val emptyState = AapNavigationHelper.NavigationSnapshot(
            navigationState = timed(NavigationStatus.NavigationState.getDefaultInstance())
        )

        assertEquals(
            "{\"event\":\"Maneuver.ExitNumber\",\"value\":null}",
            AapMviteNavigationMapper.map(emptyState)
                .first { it.event == "Maneuver.ExitNumber" }
                .toJson()
        )
    }

    @Test
    fun navigationState_wireTagsKeepInstructionAndNextRoadInTheirActualFields() {
        val instruction = protobuf { it.writeString(1, "Turn right") }
        val roadInfo = protobuf { it.writeString(1, "Main Street") }
        val step = protobuf {
            it.writeByteArray(2, instruction)
            it.writeByteArray(4, roadInfo)
        }
        val stateBytes = protobuf { it.writeByteArray(1, step) }
        val snapshot = AapNavigationHelper.NavigationSnapshot(
            navigationState = timed(NavigationStatus.NavigationState.parseFrom(stateBytes))
        )

        val events = AapMviteNavigationMapper.map(snapshot)

        assertEquals(
            "{\"event\":\"Maneuver\",\"distance\":null,\"metric\":null,\"nextRoad\":\"Main Street\",\"sign\":null}",
            events.first { it.event == "Maneuver" }.toJson()
        )
        assertEquals(
            "{\"event\":\"RouteStatus\",\"value\":\"Turn right\"}",
            events.first { it.event == "RouteStatus" }.toJson()
        )
    }

    @Test
    fun routeActive_mapsAllClusterStatesAndExplicitLifecycleMessages() {
        val expected = mapOf(
            NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.UNAVAILABLE to false,
            NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.ACTIVE to true,
            NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.INACTIVE to false,
            NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.REROUTING to true
        )

        expected.forEach { (status, active) ->
            val snapshot = AapNavigationHelper.NavigationSnapshot(
                clusterStatus = timed(
                    NavigationStatus.NavigationClusterStatus.newBuilder()
                        .setStatus(status)
                        .build()
                )
            )
            assertEquals(
                AapMviteNavigationMapper.routeActive(active).toJson(),
                AapMviteNavigationMapper.map(snapshot).single().toJson()
            )
        }
    }

    @Test
    fun speed_usesKphAndKeepsOneDecimalWhenNeeded() {
        assertEquals(
            "{\"event\":\"Speed\",\"value\":72}",
            AapMviteNavigationMapper.speed(72.0).toJson()
        )
        assertEquals(
            "{\"event\":\"Speed\",\"value\":72.4}",
            AapMviteNavigationMapper.speed(72.44).toJson()
        )
    }

    @Test
    fun distances_preferAndroidAutoDisplayValueForMviteUnits() {
        val displayDistance = NavigationStatus.NavigationDistance.newBuilder()
            .setMeters(1_700)
            .setDisplayValue("1,7")
            .setDisplayUnits(NavigationStatus.NavigationDistance.DistanceUnits.KILOMETERS_P1)
            .build()
        val snapshot = AapNavigationHelper.NavigationSnapshot(
            navigationState = timed(
                NavigationStatus.NavigationState.newBuilder()
                    .addSteps(NavigationStatus.NavigationStep.newBuilder().build())
                    .build()
            ),
            currentPosition = timed(
                NavigationStatus.NavigationCurrentPosition.newBuilder()
                    .setStepDistance(
                        NavigationStatus.NavigationStepDistance.newBuilder()
                            .setDistance(displayDistance)
                            .build()
                    )
                    .addDestinationDistances(
                        NavigationStatus.NavigationDestinationDistance.newBuilder()
                            .setDistance(displayDistance)
                            .build()
                    )
                    .build()
            )
        )

        assertEquals(
            "{\"event\":\"Maneuver\",\"distance\":1.7,\"metric\":\"км\",\"nextRoad\":null,\"sign\":null}",
            AapMviteNavigationMapper.map(snapshot).first { it.event == "Maneuver" }.toJson()
        )
        assertEquals(
            "{\"event\":\"Eta.DistanceLeft\",\"value\":1.7,\"metric\":\"км\"}",
            AapMviteNavigationMapper.map(snapshot).first { it.event == "Eta.DistanceLeft" }.toJson()
        )
    }

    @Test
    fun emissionPolicy_ordersLifecycleDeduplicatesAndResetsAtRouteEnd() {
        val policy = AapMviteNavigationEmissionPolicy()
        val sessionToken = policy.beginSession()
        val snapshot = maneuverSnapshot(distanceMeters = 350)
        val firstMapped = AapMviteNavigationMapper.map(snapshot)

        assertEquals(
            listOf("RouteActive", "Maneuver", "Maneuver.ExitNumber", "Lanes"),
            policy.select(firstMapped, sessionToken = sessionToken).map { it.event }
        )
        assertEquals(sessionToken, policy.beginSession())
        assertTrue(policy.select(firstMapped, sessionToken = sessionToken).isEmpty())
        assertEquals(
            listOf("Speed"),
            policy.selectSpeed(AapMviteNavigationMapper.speed(42.0)).map { it.event }
        )
        assertTrue(policy.selectSpeed(AapMviteNavigationMapper.speed(42.0)).isEmpty())

        assertEquals(
            listOf("Maneuver"),
            policy.select(
                AapMviteNavigationMapper.map(maneuverSnapshot(300)),
                sessionToken = sessionToken
            ).map { it.event }
        )
        assertEquals(listOf("RouteActive"), policy.endSession().map { it.event })
        assertTrue(policy.endSession().isEmpty())
        assertTrue(policy.selectSpeed(AapMviteNavigationMapper.speed(43.0)).isEmpty())
        assertTrue(policy.select(emptyList(), routeActiveOverride = false, sessionToken = sessionToken).isEmpty())
        assertTrue(policy.select(firstMapped, sessionToken = sessionToken).isEmpty())

        val restartedToken = policy.beginSession()
        assertEquals(
            listOf("RouteActive", "Maneuver", "Maneuver.ExitNumber", "Lanes"),
            policy.select(
                firstMapped,
                routeActiveOverride = true,
                sessionToken = restartedToken
            ).map { it.event }
        )

        val unknownPolicy = AapMviteNavigationEmissionPolicy()
        val unknownToken = unknownPolicy.beginSession()
        val emptyState = AapNavigationHelper.NavigationSnapshot(
            navigationState = timed(NavigationStatus.NavigationState.getDefaultInstance())
        )
        assertEquals(
            listOf("Maneuver.ExitNumber", "Lanes"),
            AapMviteNavigationMapper.map(emptyState).map { it.event }
        )
        assertTrue(
            unknownPolicy.select(
                AapMviteNavigationMapper.map(emptyState),
                sessionToken = unknownToken
            ).isEmpty()
        )

        val lanePolicy = AapMviteNavigationEmissionPolicy()
        val laneToken = lanePolicy.beginSession()
        val nonEmptyLanes = AapMviteNavigationEvent(
            event = "Lanes",
            encoded = "{\"event\":\"Lanes\",\"items\":[{\"idx\":0}]}"
        )
        assertEquals(
            listOf("RouteActive", "Lanes"),
            lanePolicy.select(
                listOf(nonEmptyLanes),
                routeActiveOverride = true,
                sessionToken = laneToken
            ).map { it.event }
        )
        assertEquals(
            listOf("Maneuver.ExitNumber", "Lanes", "RouteActive"),
            lanePolicy.endSession().map { it.event }
        )
        assertTrue(lanePolicy.selectSpeed(AapMviteNavigationMapper.speed(10.0)).isEmpty())
        assertTrue(lanePolicy.select(listOf(nonEmptyLanes), sessionToken = laneToken).isEmpty())
        val nextLaneToken = lanePolicy.beginSession()
        assertEquals(
            listOf("RouteActive", "Lanes"),
            lanePolicy.select(
                listOf(nonEmptyLanes),
                routeActiveOverride = true,
                sessionToken = nextLaneToken
            ).map { it.event }
        )
    }

    @Test
    fun emissionPolicy_rejectsDelayedSnapshotFromEndedSession() {
        val policy = AapMviteNavigationEmissionPolicy()
        val mapped = AapMviteNavigationMapper.map(maneuverSnapshot(distanceMeters = 350))
        val oldToken = policy.beginSession()

        assertTrue(policy.select(mapped, sessionToken = oldToken).isNotEmpty())
        assertTrue(policy.endSession().isNotEmpty())
        assertTrue(policy.select(mapped, sessionToken = oldToken).isEmpty())
        assertTrue(policy.selectSpeed(AapMviteNavigationMapper.speed(10.0)).isEmpty())

        val newToken = policy.beginSession()
        assertTrue(policy.select(mapped, sessionToken = newToken).isNotEmpty())
    }

    @Test
    fun emissionPolicy_clearsVisibleExitNumberAtSessionEnd() {
        val policy = AapMviteNavigationEmissionPolicy()
        val token = policy.beginSession()
        policy.select(
            listOf(
                AapMviteNavigationMapper.routeActive(true),
                AapMviteNavigationMapper.exitNumber(4)
            ),
            sessionToken = token
        )

        assertEquals(
            listOf(
                "{\"event\":\"Maneuver.ExitNumber\",\"value\":null}",
                "{\"event\":\"Lanes\",\"items\":[]}",
                "{\"event\":\"RouteActive\",\"value\":false}"
            ),
            policy.endSession().map { it.toJson() }
        )
    }

    private fun maneuverSnapshot(distanceMeters: Int): AapNavigationHelper.NavigationSnapshot =
        AapNavigationHelper.NavigationSnapshot(
            navigationState = timed(
                NavigationStatus.NavigationState.newBuilder()
                    .addSteps(
                        NavigationStatus.NavigationStep.newBuilder()
                            .setManeuver(
                                NavigationStatus.NavigationManeuver.newBuilder()
                                    .setType(NavigationType.TURN_NORMAL_RIGHT)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            ),
            currentPosition = timed(
                NavigationStatus.NavigationCurrentPosition.newBuilder()
                    .setStepDistance(
                        NavigationStatus.NavigationStepDistance.newBuilder()
                            .setDistance(distance(distanceMeters))
                            .build()
                    )
                    .build()
            )
        )

    private fun distance(meters: Int): NavigationStatus.NavigationDistance =
        NavigationStatus.NavigationDistance.newBuilder()
            .setMeters(meters)
            .build()

    private fun <T> timed(payload: T): AapNavigationHelper.TimedMessage<T> =
        AapNavigationHelper.TimedMessage(payload, 1L)

    private fun protobuf(write: (CodedOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = CodedOutputStream.newInstance(bytes)
        write(output)
        output.flush()
        return bytes.toByteArray()
    }
}
