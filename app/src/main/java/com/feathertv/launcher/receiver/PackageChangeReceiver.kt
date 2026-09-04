package com.feathertv.launcher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.feathertv.launcher.MainActivity

class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_PACKAGE_ADDED ||
            action == Intent.ACTION_PACKAGE_REMOVED ||
            action == Intent.ACTION_PACKAGE_REPLACED
        ) {
            MainActivity.instance?.reloadApps()
        }
    }
}
