package com.miauto.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService

object CarLinkTransportUi {
    fun title(mode: String): Int = when (mode) {
        CarLinkTransport.P2P -> R.string.carlink_transport_p2p
        CarLinkTransport.LOCAL_HOTSPOT -> R.string.carlink_transport_hotspot
        else -> R.string.carlink_transport_auto
    }

    fun applyAndExit(activity: Activity, mode: String) {
        if (mode == CarLinkTransport.LOCAL_HOTSPOT && Build.VERSION.SDK_INT < 28) {
            Toast.makeText(activity, R.string.carlink_transport_not_supported, Toast.LENGTH_LONG).show()
            return
        }
        val context = activity.applicationContext
        if (!context.getSharedPreferences(CarLinkTransport.PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(CarLinkTransport.KEY, mode).commit()) return
        App.provide(context).icocaBridge.stop()
        AapService.killProcessOnDestroy = true
        activity.finishAffinity()
        if (!context.stopService(Intent(context, AapService::class.java))) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
