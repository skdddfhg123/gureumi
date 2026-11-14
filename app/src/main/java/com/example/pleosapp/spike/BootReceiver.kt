package com.example.pleosapp.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager

/**
 * 시동을 걸면 사람이 앱을 켜지 않아도 서비스가 돌게 합니다. Suspend 복귀에는 이 방송이 오지 않을 수 있어
 * 앱 실행(구름이 홈)을 두 번째 시작 경로로 둡니다. Pleos가 상주 서비스를 허용하는지는 운영사무국 문의 대상입니다.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in START_ACTIONS || isHeadlessSystemUser(context)) return
        runCatching { VehicleProbeService.start(context) }
            .onFailure { ProbeState.log("FAIL boot start ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** 헤드리스 시스템 사용자는 운전자가 아니므로 서비스를 띄우지 않습니다. */
    private fun isHeadlessSystemUser(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && UserManager.isHeadlessSystemUserMode() &&
            context.getSystemService(UserManager::class.java).isSystemUser

    private companion object {
        val START_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
