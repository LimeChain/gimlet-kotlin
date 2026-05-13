package com.limechain.gimlet

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/**
 * Surfaces [GimletStatusBarWidget] in the IDE status bar for every project.
 *
 * The widget is always created - Gimlet engages lazily, sitting passive
 * until the user runs a debug-enabled test. If the port stays unbound,
 * the widget reads "Gimlet: Idle" forever; cost is one MessageBus
 * subscription + one observer slot on the state monitor (which doesn't
 * actually poll until something is observed).
 */
internal class GimletStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = GimletStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "Gimlet"

    override fun createWidget(project: Project): StatusBarWidget = GimletStatusBarWidget(project)

    override fun isAvailable(project: Project): Boolean = true
}
