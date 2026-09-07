package com.andrerinas.openheadunit.aap

import android.content.Context
import com.andrerinas.openheadunit.contract.MviteNavigationIntent
import com.andrerinas.openheadunit.utils.AppLog

/** Selects ordered, changed-only MVite events without depending on Android runtime classes. */
internal class AapMviteNavigationEmissionPolicy {
    private val lastJsonByEvent = mutableMapOf<String, String>()
    private var routeActive: Boolean? = null
    private var sessionGeneration = 0L
    private var sessionOpen = false

    @Synchronized
    fun beginSession(): Long {
        if (sessionOpen) return sessionGeneration
        sessionGeneration += 1L
        sessionOpen = true
        lastJsonByEvent.clear()
        routeActive = null
        return sessionGeneration
    }

    @Synchronized
    fun isSessionActive(sessionToken: Long): Boolean =
        sessionOpen && sessionToken == sessionGeneration

    @Synchronized
    fun select(
        mappedEvents: List<AapMviteNavigationEvent>,
        routeActiveOverride: Boolean? = null,
        sessionToken: Long? = null
    ): List<AapMviteNavigationEvent> {
        if (!sessionOpen || (sessionToken != null && sessionToken != sessionGeneration)) {
            return emptyList()
        }
        return selectActiveSession(mappedEvents, routeActiveOverride)
    }

    private fun selectActiveSession(
        mappedEvents: List<AapMviteNavigationEvent>,
        routeActiveOverride: Boolean?
    ): List<AapMviteNavigationEvent> {
        val selected = mutableListOf<AapMviteNavigationEvent>()
        val mappedRouteActive = mappedEvents.firstOrNull { it.event == EVENT_ROUTE_ACTIVE }
            ?.routeActiveValue
        val effectiveRouteActive = routeActiveOverride ?: mappedRouteActive
        val routeEvents = mappedEvents.filterNot { it.event == EVENT_ROUTE_ACTIVE }

        if (effectiveRouteActive == false) {
            val emptyExitNumber = AapMviteNavigationMapper.exitNumber(null)
            val emptyLanes = AapMviteNavigationMapper.lanesEmpty()
            val inactiveRoute = AapMviteNavigationMapper.routeActive(false)
            appendChanged(selected, emptyExitNumber)
            appendChanged(selected, emptyLanes)
            appendChanged(selected, inactiveRoute)
            lastJsonByEvent.clear()
            lastJsonByEvent[EVENT_EXIT_NUMBER] = emptyExitNumber.toJson()
            lastJsonByEvent[EVENT_LANES] = emptyLanes.toJson()
            lastJsonByEvent[EVENT_ROUTE_ACTIVE] = inactiveRoute.toJson()
            routeActive = false
            return selected
        }

        val hasRouteActivityEvidence = routeEvents.any { it.routeActivityEvidence }
        if (effectiveRouteActive == true || (hasRouteActivityEvidence && routeActive == null)) {
            appendChanged(selected, AapMviteNavigationMapper.routeActive(true))
            routeActive = true
        }

        if (routeActive == true) {
            routeEvents.forEach { appendChanged(selected, it) }
        }
        return selected
    }

    @Synchronized
    fun selectSpeed(event: AapMviteNavigationEvent): List<AapMviteNavigationEvent> {
        if (!sessionOpen || routeActive != true) return emptyList()
        return buildList { appendChanged(this, event) }
    }

    @Synchronized
    fun endSession(): List<AapMviteNavigationEvent> {
        if (!sessionOpen) return emptyList()
        val selected = selectActiveSession(emptyList(), routeActiveOverride = false)
        sessionOpen = false
        sessionGeneration += 1L
        return selected
    }

    private fun appendChanged(
        destination: MutableList<AapMviteNavigationEvent>,
        event: AapMviteNavigationEvent
    ) {
        val json = event.toJson()
        if (lastJsonByEvent[event.event] == json) return
        lastJsonByEvent[event.event] = json
        destination += event
    }

    private companion object {
        const val EVENT_ROUTE_ACTIVE = "RouteActive"
        const val EVENT_EXIT_NUMBER = "Maneuver.ExitNumber"
        const val EVENT_LANES = "Lanes"
    }
}

/** Publishes the MVite-compatible event stream to the SCS package. */
object AapMviteNavigationEmitter {
    private val policy = AapMviteNavigationEmissionPolicy()

    @Synchronized
    fun beginSession(): Long = policy.beginSession()

    @Synchronized
    internal fun runIfSessionActive(sessionToken: Long, action: () -> Unit) {
        if (policy.isSessionActive(sessionToken)) action()
    }

    @Synchronized
    fun emitSnapshot(
        context: Context,
        snapshot: AapNavigationHelper.NavigationSnapshot,
        sessionToken: Long,
        routeActiveOverride: Boolean? = null
    ) {
        emit(
            context,
            policy.select(
                AapMviteNavigationMapper.map(snapshot),
                routeActiveOverride,
                sessionToken
            )
        )
    }

    @Synchronized
    fun emitSpeed(context: Context, speedMetersPerSecond: Float) {
        if (!speedMetersPerSecond.isFinite() || speedMetersPerSecond < 0f) return
        val speedKph = (speedMetersPerSecond * METERS_PER_SECOND_TO_KPH).toDouble()
        emit(context, policy.selectSpeed(AapMviteNavigationMapper.speed(speedKph)))
    }

    @Synchronized
    fun endSession(context: Context) {
        emit(context, policy.endSession())
    }

    private fun emit(context: Context, events: List<AapMviteNavigationEvent>) {
        val appContext = context.applicationContext
        events.forEach { event ->
            val payload = event.toJson()
            val size = payload.toByteArray(Charsets.UTF_8).size
            if (size > MviteNavigationIntent.MAX_PAYLOAD_BYTES) {
                AppLog.e("Nav: MVite event ${event.event} dropped: payload=$size bytes")
                return@forEach
            }
            try {
                appContext.sendBroadcast(MviteNavigationIntent(payload))
                AppLog.d("Nav: MVite event=${event.event} bytes=$size")
            } catch (error: RuntimeException) {
                AppLog.e("Nav: failed to send MVite event ${event.event}", error)
            }
        }
    }

    private const val METERS_PER_SECOND_TO_KPH = 3.6f
}
