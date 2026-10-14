package com.github.hypercookie.giteaplugin.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent

/*
 * Flow-to-Swing bindings and an Action factory. The collaboration tools
 * module has equivalents (`bindVisibilityIn`, `bindTextIn`, `swingAction`
 * in `com.intellij.collaboration.ui.util`), but their files are marked
 * `@ApiStatus.Internal` (migration ledger I12), so this plugin keeps its own.
 */

/** Runs [apply] on the EDT for every value of [flow] while [cs] is alive. */
fun <T> CoroutineScope.bindOnEdt(flow: Flow<T>, apply: (T) -> Unit) {
    launch(Dispatchers.Main) { flow.collect { apply(it) } }
}

fun JComponent.bindVisibility(cs: CoroutineScope, flow: Flow<Boolean>) = cs.bindOnEdt(flow) { isVisible = it }

fun Action.bindEnabled(cs: CoroutineScope, flow: Flow<Boolean>) = cs.bindOnEdt(flow) { isEnabled = it }

fun Action.bindName(cs: CoroutineScope, flow: Flow<String>) = cs.bindOnEdt(flow) { putValue(Action.NAME, it) }

fun giteaAction(name: String, perform: (ActionEvent) -> Unit): Action = object : AbstractAction(name) {
    override fun actionPerformed(e: ActionEvent) = perform(e)
}
