package com.wall.launcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Passive, zero-payload NotificationListenerService.
 * Stores strictly a Set<String> of package names that currently have active notifications
 * so WALL can render a "Red Paint Drip" (#D8342A) beneath their stencil icons.
 */
class WallNotificationListener : NotificationListenerService() {

    companion object {
        private val _activeDripPackages = MutableStateFlow<Set<String>>(emptySet())
        val activeDripPackages: StateFlow<Set<String>> = _activeDripPackages.asStateFlow()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshPackages()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshPackages()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshPackages()
    }

    private fun refreshPackages() {
        try {
            val pkgs = activeNotifications
                ?.asSequence()
                ?.filter { !it.isOngoing }
                ?.map { it.packageName }
                ?.toSet()
                ?: emptySet()
            _activeDripPackages.value = pkgs
        } catch (_: Exception) {
        }
    }
}
