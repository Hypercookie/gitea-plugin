package com.github.hypercookie.giteaplugin.util

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.ui.ClientProperty
import javax.swing.JButton

/**
 * A `JButton` drawn in the Platform's "default" (accent, blue) style — the
 * same look a `DialogWrapper` gives its OK action. IntelliJ's convention is
 * one such button per group, on the main affirmative action (Reply, Add,
 * Submit); Cancel, Discard and secondary choices stay plain. The Platform
 * never colors destructive buttons red, so neither does this plugin.
 */
fun primaryButton(text: String): JButton = JButton(text).apply {
    ClientProperty.put(this, DarculaButtonUI.DEFAULT_STYLE_KEY, true)
}
