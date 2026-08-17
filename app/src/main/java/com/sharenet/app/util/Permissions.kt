package com.sharenet.app.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions:
 *  - Android 13+ (API 33): NEARBY_WIFI_DEVICES for Wi-Fi Direct, plus
 *    POST_NOTIFICATIONS for the foreground-service notification.
 *  - Android 12 and below (API ≤ 32): ACCESS_FINE_LOCATION is what Wi-Fi
 *    Direct discovery needs on those versions.
 */
object Permissions {

    const val REQUEST_CODE = 41

    fun required(): Array<String> {
        val list = ArrayList<String>(3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return list.toTypedArray()
    }

    fun hasAll(context: Context): Boolean =
        required().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun request(activity: Activity) {
        activity.requestPermissions(required(), REQUEST_CODE)
    }
}
