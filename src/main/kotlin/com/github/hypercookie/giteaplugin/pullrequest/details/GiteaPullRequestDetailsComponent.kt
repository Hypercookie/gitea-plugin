package com.github.hypercookie.giteaplugin.pullrequest.details

import com.github.hypercookie.giteaplugin.api.dto.GitCommit
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.ReviewEvent
import com.github.hypercookie.giteaplugin.pullrequest.list.GiteaPullRequestActions
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaSubmitReviewButton
import com.github.hypercookie.giteaplugin.pullrequest.timeline.GiteaTimelineEditor
import com.github.hypercookie.giteaplugin.util.GiteaMarkdown
import com.github.hypercookie.giteaplugin.util.bindOnEdt
import com.github.hypercookie.giteaplugin.util.errorPanel
import com.github.hypercookie.giteaplugin.util.gap
import com.intellij.collaboration.ui.SimpleHtmlPane
import com.intellij.collaboration.ui.codereview.CodeReviewProgressTreeModelFromDetails
import com.intellij.collaboration.ui.codereview.changes.CodeReviewChangeListComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsBranchComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsCommitInfoComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsCommitsComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsStatusComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsTitleComponentFactory
import com.intellij.collaboration.ui.codereview.details.CommitPresentation
import com.intellij.collaboration.ui.codereview.details.ReviewDetailsUIUtil
import com.intellij.collaboration.ui.codereview.details.data.ReviewState
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeListViewModel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.ListLayout
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.miginfocom.layout.AC
import net.miginfocom.layout.CC
import net.miginfocom.layout.LC
import net.miginfocom.swing.MigLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Pull request tab content. Layout and parts follow the GitHub plugin's
 * `GHPRDetailsComponentFactory` (title with "View Timeline", commits popup
 * and branch chip, commit info, changes tree, status, review actions),
 * using the platform's `CodeReviewDetails*ComponentFactory` pieces.
 */
object GiteaPullRequestDetailsComponent {

    fun create(cs: CoroutineScope, data: GiteaPullRequestReviewData): JComponent {
        val detailsVm = GiteaPullRequestDetailsViewModel(data)
        val changesVm = GiteaPullRequestChangesViewModel(cs, data)
        val branchesVm = GiteaPullRequestBranchesViewModel(cs, data)
        val statusVm = GiteaPullRequestStatusViewModel(cs, data)

        val popupGroup = DefaultActionGroup(
            action("Refresh Pull Request", AllIcons.Actions.Refresh) { data.reload() },
            action("Open in Browser", AllIcons.General.Web) { GiteaPullRequestActions.openInBrowser(data.pullRequest.value.htmlUrl) },
            action("Copy Link", AllIcons.Actions.Copy) { GiteaPullRequestActions.copyLink(data.pullRequest.value.htmlUrl) },
        )

        val title = CodeReviewDetailsTitleComponentFactory.create(cs, detailsVm, "Open on Gitea", popupGroup) { SimpleHtmlPane() }
        val timelineLink = ActionLink("View Timeline") { GiteaTimelineEditor.open(data) }

        val commitsAndBranches = JPanel(MigLayout(LC().insets("0").gridGap("0", "0").fill(), AC().gap("push"))).apply {
            isOpaque = false
            add(CodeReviewDetailsCommitsComponentFactory.create(cs, changesVm, ::present))
            add(CodeReviewDetailsBranchComponentFactory.create(cs, branchesVm))
        }
        val commitInfo = CodeReviewDetailsCommitInfoComponentFactory.create(cs, changesVm.selectedCommit, ::present) { GiteaMarkdown.createPane("") }

        val status = JPanel(ListLayout.vertical(0)).apply {
            isOpaque = false
            add(CodeReviewDetailsStatusComponentFactory.createCiComponent(cs, statusVm))
            add(mergeabilityRow(cs, statusVm))
            add(CodeReviewDetailsStatusComponentFactory.createNeedReviewerComponent(cs, reviewerStates(cs, data)))
        }

        val actions = JPanel(ListLayout.horizontal(0)).apply {
            isOpaque = false
            add(GiteaSubmitReviewButton.create(cs, data, above = true))
        }

        return JPanel(MigLayout(LC().insets("0").gridGap("0", "0").fill().flowY().noGrid().hideMode(3))).apply {
            isOpaque = false
            background = UIUtil.getListBackground()
            add(ReviewDetailsUIUtil.createTitlePanel(title, timelineLink), CC().growX().gap(ReviewDetailsUIUtil.TITLE_GAPS))
            add(commitsAndBranches, CC().growX().gap(ReviewDetailsUIUtil.COMMIT_POPUP_BRANCHES_GAPS))
            add(commitInfo, CC().growX().gap(ReviewDetailsUIUtil.COMMIT_INFO_GAPS))
            add(createChangesComponent(cs, changesVm), CC().grow().shrinkPrioY(200))
            add(status, CC().growX().gap(ReviewDetailsUIUtil.STATUSES_GAPS).maxHeight("${ReviewDetailsUIUtil.STATUSES_MAX_HEIGHT}"))
            add(actions, CC().growX().pushX().gap(ReviewDetailsUIUtil.ACTIONS_GAPS).minHeight("pref"))
            PopupHandler.installPopupMenu(this, popupGroup, ActionPlaces.POPUP)
        }
    }

    private fun createChangesComponent(cs: CoroutineScope, changesVm: GiteaPullRequestChangesViewModel): JComponent {
        val wrapper = Wrapper(JBLabel("Loading…"))
        cs.bindOnEdt(changesVm.changeListVm) { result ->
            val content = when {
                result == null -> JBLabel("Loading…")
                result.isFailure -> errorPanel("Can't load changes", result.exceptionOrNull()!!)
                else -> {
                    val vm = result.getOrThrow()
                    val progress = CodeReviewProgressTreeModelFromDetails(cs, vm)
                    val tree = CodeReviewChangeListComponentFactory.createIn(cs, vm, progress, "Pull request does not contain any changes")
                    tree.installPopupHandler(
                        DefaultActionGroup(
                            ActionManager.getInstance().getAction("Diff.ShowDiff"),
                            Separator.getInstance(),
                            ActionManager.getInstance().getAction("ExpandAll"),
                            ActionManager.getInstance().getAction("CollapseAll"),
                        ),
                    )
                    UiDataProvider.wrapComponent(ScrollPaneFactory.createScrollPane(tree, true)) { sink ->
                        sink[CodeReviewChangeListViewModel.DATA_KEY] = vm
                    }
                }
            }
            wrapper.setContent(content)
            wrapper.revalidate()
        }
        return wrapper
    }

    /** Requested reviewers without a review are "waiting for review";
     * reviewers who approved or requested changes are reported as such. */
    private fun reviewerStates(cs: CoroutineScope, data: GiteaPullRequestReviewData): MutableStateFlow<Map<GiteaUser, ReviewState>> {
        val states = MutableStateFlow<Map<GiteaUser, ReviewState>>(emptyMap())
        cs.launch {
            data.pullRequest.collect { pr ->
                val reviews = runCatching { data.client.listReviews(data.owner, data.repoName, pr.number) }.getOrDefault(emptyList())
                val byUser = LinkedHashMap<GiteaUser, ReviewState>()
                pr.requestedReviewers.orEmpty().forEach { byUser[it] = ReviewState.NEED_REVIEW }
                reviews.filter { !it.dismissed }.forEach { review ->
                    val user = review.user ?: return@forEach
                    when (review.state) {
                        ReviewEvent.APPROVED -> byUser[user] = ReviewState.ACCEPTED
                        ReviewEvent.REQUEST_CHANGES -> byUser[user] = ReviewState.WAIT_FOR_UPDATES
                        else -> Unit
                    }
                }
                states.value = byUser
            }
        }
        return states
    }

    private fun present(commit: GitCommit): CommitPresentation {
        val message = commit.commit.message
        val headline = message.substringBefore('\n')
        val body = message.substringAfter('\n', "").trim()
        return CommitPresentation(
            titleHtml = GiteaMarkdown.toHtml(headline),
            descriptionHtml = GiteaMarkdown.toHtml(body),
            author = commit.author?.login ?: commit.commit.author.name,
            committedDate = commit.commit.author.date.toDate() ?: java.util.Date(0),
        )
    }

    /**
     * Same look as the platform's conflicts row, but Gitea's wording: its
     * `mergeable` flag can't tell conflicts from a failed check, so the
     * platform's "Branch has conflicts" text would overstate it.
     */
    private fun mergeabilityRow(cs: CoroutineScope, statusVm: GiteaPullRequestStatusViewModel): JComponent {
        val label = JBLabel()
        val row = JPanel(ListLayout.horizontal(8)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(5, 0)
            add(label)
        }
        cs.bindOnEdt(statusVm.mergeability) { state ->
            row.isVisible = state != GiteaMergeability.OK
            when (state) {
                GiteaMergeability.CHECKING -> {
                    label.icon = CIBuildStatusIcons.pending
                    label.text = "Checking whether the branch can be merged"
                }
                GiteaMergeability.BLOCKED -> {
                    label.icon = CIBuildStatusIcons.failed
                    label.text = "Branch can't be merged automatically"
                    label.toolTipText = "Gitea reports conflicts with the target branch, or it could not check them"
                }
                GiteaMergeability.OK -> Unit
            }
        }
        return row
    }

    private fun action(text: String, icon: javax.swing.Icon, perform: () -> Unit) = object : DumbAwareAction(text, null, icon) {
        override fun actionPerformed(e: AnActionEvent) = perform()
    }
}
