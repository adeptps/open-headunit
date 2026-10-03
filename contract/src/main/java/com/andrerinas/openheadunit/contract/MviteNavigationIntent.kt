package com.andrerinas.openheadunit.contract

import android.content.Intent

/**
 * Explicit broadcast compatible with the Yandex Navigator MVite hook consumed by SCS.
 * The payload contains one MVite JSON event.
 */
class MviteNavigationIntent(payload: String, sourceBuild: String? = null) : Intent(ACTION) {
    init {
        setPackage(SCS_PACKAGE)
        putExtra(EXTRA_PAYLOAD, payload)
        sourceBuild?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_OPEN_HEADUNIT_BUILD, it) }
    }

    companion object {
        const val ACTION = "ssa.labs.monjaro.YN_HOOK"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_OPEN_HEADUNIT_BUILD = "open_headunit_build"
        const val SCS_PACKAGE = "com.example.climateseats"
        const val MAX_PAYLOAD_BYTES = 64 * 1024
    }
}
