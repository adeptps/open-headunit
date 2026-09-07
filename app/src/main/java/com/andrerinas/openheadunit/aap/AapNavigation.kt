package com.andrerinas.openheadunit.aap

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * Handles navigation messages from the ID_NAV channel from any Android Auto-enabled app
 * (Google Maps, Yandex Maps, etc.). Shows notifications with turn-by-turn directions and current street.
 */
class AapNavigation(
    private val context: Context,
    private val settings: Settings
) {
    private val helper = AapNavigationHelper(context)
    private val snapshot = AapNavigationHelper.NavigationSnapshot()
    private val debounceHandler = Handler(Looper.getMainLooper())
    private val mviteSessionToken = AapMviteNavigationEmitter.beginSession()
    private var isBroadcastScheduled = false
    private var pendingNavEventType = NAV_EVENT_TYPE_TURN

    private val debouncedBroadcastEmitter = Runnable {
        val pending = synchronized(this) {
            if (!isBroadcastScheduled) return@Runnable
            isBroadcastScheduled = false
            snapshot.copy() to pendingNavEventType
        }
        AapMviteNavigationEmitter.runIfSessionActive(mviteSessionToken) {
            helper.sendFullNavigationBroadcast(pending.first, pending.second)
            AapMviteNavigationEmitter.emitSnapshot(
                context = context,
                snapshot = pending.first,
                sessionToken = mviteSessionToken
            )
        }
    }

    @Synchronized
    fun process(message: AapMessage): Boolean {
        if (message.channel != Channel.ID_NAV) return false

        return when (message.type) {
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_START_VALUE -> {
                AppLog.d("Nav: Instrument cluster start")
                clearAccumulatedData()
                AapMviteNavigationEmitter.emitSnapshot(
                    context,
                    snapshot,
                    routeActiveOverride = true,
                    sessionToken = mviteSessionToken
                )
                scheduleDebouncedBroadcast(NAV_EVENT_TYPE_START)
                true
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_STOP_VALUE -> {
                AppLog.d("Nav: Instrument cluster stop")
                clearAccumulatedData()
                AapMviteNavigationEmitter.emitSnapshot(
                    context,
                    snapshot,
                    routeActiveOverride = false,
                    sessionToken = mviteSessionToken
                )
                scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STOP)
                helper.cancelNotification()
                true
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE -> {
                try {
                    val status = message.parse(NavigationStatus.NavigationClusterStatus.newBuilder()).build()
                    AppLog.d("Nav: Navigation status=${status.status}")
                    updateClusterStatus(status)
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STATUS)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationClusterStatus", e)
                    true
                }
            }
            NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE -> {
                try {
                    val payload = message.data.copyOfRange(message.dataOffset, message.size)
                    when (val decoded = AapModernNavigationTurnDecoder.classify(payload)) {
                        is AapNavigationTurnDecodeResult.Corrected -> {
                            val modernTurn = decoded.turn
                            snapshot.nextTurnDetail = null
                            snapshot.modernTurn = AapNavigationHelper.TimedMessage(
                                modernTurn,
                                helper.nowElapsedRealtimeMs()
                            )
                            modernTurn.roadName?.let {
                                snapshot.currentStreet = AapNavigationHelper.TimedMessage(
                                    it,
                                    helper.nowElapsedRealtimeMs()
                                )
                            }
                            AppLog.d(
                                "Nav: corrected flat turn road=${modernTurn.roadName.orEmpty()} " +
                                    "maneuver=${modernTurn.maneuverType ?: -1} " +
                                    "distance=${modernTurn.distanceMeters ?: -1}"
                            )
                            scheduleDebouncedBroadcast(NAV_EVENT_TYPE_TURN)
                            if (settings.showNavigationNotifications) {
                                helper.showNotificationForSnapshot(snapshot, modernTurn.distanceMeters)
                            }
                            return true
                        }
                        AapNavigationTurnDecodeResult.Ambiguous -> {
                            AppLog.d("Nav: ambiguous 0x8004 layout ignored pending rich NAV state")
                            return true
                        }
                        AapNavigationTurnDecodeResult.Invalid -> {
                            AppLog.d("Nav: invalid 0x8004 payload ignored")
                            return true
                        }
                        AapNavigationTurnDecodeResult.Legacy -> Unit
                    }
                    val detail = message.parse(NavigationStatus.NextTurnDetail.newBuilder()).buildPartial()
                    snapshot.modernTurn = null
                    snapshot.nextTurnDetail = AapNavigationHelper.TimedMessage(detail, helper.nowElapsedRealtimeMs())
                    val road = detail.road.takeIf { it.isNotBlank() }
                    road?.let {
                        snapshot.currentStreet = AapNavigationHelper.TimedMessage(it, helper.nowElapsedRealtimeMs())
                    }
                    AppLog.d(
                        "Nav: NextTurnDetail road=${detail.road} " +
                                "hasNextTurn=${detail.hasNextTurn()} nextTurn=${detail.nextTurn}"
                    )
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_TURN)
                    if (settings.showNavigationNotifications) {
                        helper.showNotificationForSnapshot(snapshot, distanceMeters = null)
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NextTurnDetail", e)
                    true
                }
            }
            NavigationStatus.MsgType.NEXTTURNDISTANCEANDTIME_VALUE -> {
                // AA versions reuse these four varint tags with conflicting semantics. Rich
                // 0x8007 and corrected 0x8004 data remain authoritative until a capture proves it.
                AppLog.d("Nav: ambiguous 0x8005 layout ignored pending wire capture")
                true
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE -> {
                try {
                    val state = message.parse(NavigationStatus.NavigationState.newBuilder()).build()
                    snapshot.navigationState = AapNavigationHelper.TimedMessage(state, helper.nowElapsedRealtimeMs())
                    AapMviteNavigationEmitter.emitSnapshot(
                        context,
                        snapshot,
                        sessionToken = mviteSessionToken
                    )
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STATE)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationState", e)
                    true
                }
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE -> {
                try {
                    val position = message.parse(NavigationStatus.NavigationCurrentPosition.newBuilder()).build()
                    snapshot.currentPosition = AapNavigationHelper.TimedMessage(position, helper.nowElapsedRealtimeMs())
                    val road = position
                        .takeIf { it.hasCurrentRoad() && it.currentRoad.hasName() }
                        ?.currentRoad
                        ?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: snapshot.currentStreet?.payload
                    if (!road.isNullOrBlank()) {
                        snapshot.currentStreet = AapNavigationHelper.TimedMessage(road, helper.nowElapsedRealtimeMs())
                    }
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_CURRENT_POSITION)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationCurrentPosition", e)
                    true
                }
            }
            else -> {
                AppLog.d("Nav: passthrough type ${message.type}")
                false
            }
        }
    }

    private fun clearAccumulatedData() {
        snapshot.clusterStatus = null
        snapshot.nextTurnDetail = null
        snapshot.nextTurnDistance = null
        snapshot.modernTurn = null
        snapshot.navigationState = null
        snapshot.currentPosition = null
        snapshot.currentStreet = null
    }

    private fun clearAccumulatedDataPreservingStatus(
        status: AapNavigationHelper.TimedMessage<NavigationStatus.NavigationClusterStatus>
    ) {
        clearAccumulatedData()
        snapshot.clusterStatus = status
    }

    private fun updateClusterStatus(status: NavigationStatus.NavigationClusterStatus) {
        val now = helper.nowElapsedRealtimeMs()
        val newStatus = AapNavigationHelper.TimedMessage(status, now)
        val previous = snapshot.clusterStatus?.payload?.status
        val changed = previous != null && previous != status.status
        if (changed) {
            clearAccumulatedDataPreservingStatus(newStatus)
            helper.cancelNotification()
        } else {
            snapshot.clusterStatus = newStatus
        }
    }

    private fun scheduleDebouncedBroadcast(navEventType: Int) {
        pendingNavEventType = navEventType
        if (isBroadcastScheduled) return
        isBroadcastScheduled = true
        debounceHandler.postDelayed(debouncedBroadcastEmitter, BROADCAST_DEBOUNCE_MS)
    }

    companion object {
        private const val BROADCAST_DEBOUNCE_MS = 1000L
        private const val NAV_EVENT_TYPE_TURN = 0
        private const val NAV_EVENT_TYPE_START = 1
        private const val NAV_EVENT_TYPE_STOP = 2
        private const val NAV_EVENT_TYPE_STATUS = 3
        private const val NAV_EVENT_TYPE_STATE = 4
        private const val NAV_EVENT_TYPE_CURRENT_POSITION = 5

        fun createNotificationChannel(context: Context) {
            AapNavigationHelper.createNotificationChannel(context)
        }
    }
}
