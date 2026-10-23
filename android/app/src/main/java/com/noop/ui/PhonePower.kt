package com.noop.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Whether the PHONE (not the strap) is plugged in, from a one-shot read of the sticky ACTION_BATTERY_CHANGED
 * intent, so no receiver lifecycle. Plugged rather than charging: a full battery on the charger stops reporting
 * charging but is still on power. False when unreadable, so a caller gating heavy work on it waits.
 */
internal object PhonePower {
    fun isOnExternalPower(context: Context): Boolean = runCatching {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)
}
