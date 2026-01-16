package com.securitycrux.secrux.intellij.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object SecruxNotifications {
    private const val GROUP_ID = "Secrux"

    fun info(project: Project, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(content, NotificationType.INFORMATION)
            .notify(project)
    }

    fun error(project: Project, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(content, NotificationType.ERROR)
            .notify(project)
    }
}
