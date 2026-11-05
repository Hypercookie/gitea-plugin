package com.github.hypercookie.giteaplugin.pullrequest.details

import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.util.bindOnEdt
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The changes tree with a file filter bar above it ("Changed locally",
 * "Has unresolved comments", "Has pending comments", ...). With no filter
 * active the tree shows [baseVm]; otherwise a view model over the matching
 * files, rebuilt only when that set changes.
 */
object GiteaFilteredChanges {

    fun create(
        cs: CoroutineScope,
        data: GiteaPullRequestReviewData,
        baseVm: GiteaChangeListViewModel,
        createTree: (CoroutineScope, GiteaChangeListViewModel) -> JComponent,
    ): JComponent {
        val filters = MutableStateFlow<Set<GiteaFileFilter>>(emptySet())
        val extensions = MutableStateFlow<Set<String>>(emptySet())
        val availableExtensions = baseVm.changes
            .map { GiteaChangeFilters.extensionOf(GiteaChangeFilters.pathOf(data, it).orEmpty()) }
            .toSortedSet()

        val content = Wrapper()
        var treeScope: CoroutineScope? = null
        val filterContext = combine(data.threads, data.pending, data.currentUser, GiteaChangeFilters.localChanges(data)) { threads, pending, me, local ->
            GiteaChangeFilters.context(data, threads, pending, me, local)
        }
        val shown = combine(filters, extensions, filterContext) { f, e, c ->
            GiteaChangeFilters.apply(baseVm.changes, { GiteaChangeFilters.pathOf(data, it) }, f, e, c)
        }.distinctUntilChanged()

        val status = JBLabel().apply { border = JBUI.Borders.empty(0, 6) }
        cs.bindOnEdt(combine(shown, filters, extensions) { list, f, e -> Triple(list, f, e) }) { (list, f, e) ->
            treeScope?.cancel()
            val scope = CoroutineScope(cs.coroutineContext + SupervisorJob(cs.coroutineContext[Job])).also { treeScope = it }
            val active = f.isNotEmpty() || e.isNotEmpty()
            val vm = if (active) GiteaChangeListViewModel(scope, data, list) else baseVm
            status.text = if (active) "${list.size} of ${baseVm.changes.size} files" else ""
            content.setContent(createTree(scope, vm))
            content.revalidate()
            content.repaint()
        }

        val group = DefaultActionGroup().apply {
            add(FilterPopupAction(filters, extensions, availableExtensions))
            add(ResetFiltersAction(filters, extensions))
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("GiteaChangesFilter", group, true).apply {
            targetComponent = content
            setReservePlaceAutoPopupIcon(false)
        }
        cs.bindOnEdt(combine(filters, extensions) { f, e -> f to e }) { toolbar.updateActionsAsync() }
        val bar = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(toolbar.component.apply { isOpaque = false }, BorderLayout.WEST)
            add(status, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(bar, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }
    }

    private class FilterPopupAction(
        private val filters: MutableStateFlow<Set<GiteaFileFilter>>,
        private val extensions: MutableStateFlow<Set<String>>,
        private val availableExtensions: Set<String>,
    ) : DumbAwareAction("Filter Files", "Show only the files that match", AllIcons.General.Filter) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val active = filters.value.size + extensions.value.size
            e.presentation.icon = if (active > 0) AllIcons.General.FilterEdited else AllIcons.General.Filter
        }

        override fun actionPerformed(e: AnActionEvent) {
            val group = DefaultActionGroup()
            fun toggles(vararg items: GiteaFileFilter) = items.forEach { group.add(FilterToggle(it, filters)) }
            toggles(GiteaFileFilter.CHANGED_LOCALLY)
            group.add(Separator.create("Comments"))
            toggles(
                GiteaFileFilter.UNRESOLVED_COMMENTS,
                GiteaFileFilter.PENDING_COMMENTS,
                GiteaFileFilter.MY_COMMENTS,
                GiteaFileFilter.ANY_COMMENTS,
                GiteaFileFilter.NO_COMMENTS,
            )
            group.add(Separator.create("Change type"))
            toggles(GiteaFileFilter.ADDED, GiteaFileFilter.MODIFIED, GiteaFileFilter.DELETED, GiteaFileFilter.RENAMED)
            if (availableExtensions.size > 1) {
                group.add(Separator.create("File type"))
                availableExtensions.forEach { group.add(ExtensionToggle(it, extensions)) }
            }
            JBPopupFactory.getInstance()
                .createActionGroupPopup(null, group, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
                .showUnderneathOf(e.inputEvent?.component ?: return)
        }
    }

    private class FilterToggle(
        private val filter: GiteaFileFilter,
        private val state: MutableStateFlow<Set<GiteaFileFilter>>,
    ) : ToggleAction(filter.title) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = filter in state.value
        override fun setSelected(e: AnActionEvent, selected: Boolean) {
            state.value = if (selected) state.value + filter else state.value - filter
        }
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.keepPopupOnPerform = KeepPopupOnPerform.Always
        }
    }

    private class ExtensionToggle(
        private val extension: String,
        private val state: MutableStateFlow<Set<String>>,
    ) : ToggleAction(if (extension.isEmpty()) "No extension" else ".$extension") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = extension in state.value
        override fun setSelected(e: AnActionEvent, selected: Boolean) {
            state.value = if (selected) state.value + extension else state.value - extension
        }
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.keepPopupOnPerform = KeepPopupOnPerform.Always
        }
    }

    private class ResetFiltersAction(
        private val filters: MutableStateFlow<Set<GiteaFileFilter>>,
        private val extensions: MutableStateFlow<Set<String>>,
    ) : DumbAwareAction("Clear Filters", null, AllIcons.Actions.Close) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = filters.value.isNotEmpty() || extensions.value.isNotEmpty()
        }
        override fun actionPerformed(e: AnActionEvent) {
            filters.value = emptySet()
            extensions.value = emptySet()
        }
    }
}
