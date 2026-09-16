package com.ssafy.ddubi

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private companion object {
        const val APP_ICON_CHANNEL = "com.ssafy.ddubi/app_icon"
        const val DAY_ICON_ALIAS = "com.ssafy.ddubi.DayIconAlias"
        const val NIGHT_ICON_ALIAS = "com.ssafy.ddubi.NightIconAlias"
        const val APP_PERMISSION_CHANNEL = "com.ssafy.ddubi/app_permissions"
        const val LOCATION_PERMISSION_REQUEST = 7102
    }

    private var locationPermissionResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            APP_PERMISSION_CHANNEL,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "hasLocationPermission" -> result.success(hasLocationPermission())
                "requestLocationPermission" -> requestLocationPermission(result)
                "openAppSettings" -> {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            APP_ICON_CHANNEL,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "getActiveIcon" -> result.success(getActiveIcon())
                "setActiveIcon" -> {
                    val icon = call.argument<String>("icon")
                    if (icon != "day" && icon != "night") {
                        result.error("INVALID_ICON", "Icon must be day or night.", null)
                        return@setMethodCallHandler
                    }

                    result.success(setActiveIcon(icon))
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun getActiveIcon(): String {
        val nightState = packageManager.getComponentEnabledSetting(
            ComponentName(this, NIGHT_ICON_ALIAS),
        )
        return if (nightState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            "night"
        } else {
            "day"
        }
    }

    private fun setActiveIcon(icon: String): Boolean {
        if (getActiveIcon() == icon) return false

        val dayAlias = ComponentName(this, DAY_ICON_ALIAS)
        val nightAlias = ComponentName(this, NIGHT_ICON_ALIAS)
        val enabledAlias = if (icon == "day") dayAlias else nightAlias
        val disabledAlias = if (icon == "day") nightAlias else dayAlias

        packageManager.setComponentEnabledSetting(
            enabledAlias,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        packageManager.setComponentEnabledSetting(
            disabledAlias,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        return true
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission(result: MethodChannel.Result) {
        if (hasLocationPermission()) {
            result.success(true)
            return
        }
        locationPermissionResult?.error(
            "REQUEST_IN_PROGRESS",
            "Permission request is already running.",
            null,
        )
        locationPermissionResult = result
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            LOCATION_PERMISSION_REQUEST,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            locationPermissionResult?.success(hasLocationPermission())
            locationPermissionResult = null
        }
    }
}
