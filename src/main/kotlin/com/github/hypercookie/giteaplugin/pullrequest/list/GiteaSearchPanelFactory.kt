package com.github.hypercookie.giteaplugin.pullrequest.list

import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaAvatarIcons
import com.github.hypercookie.giteaplugin.util.runCatchingCancellable
import com.intellij.collaboration.ui.codereview.avatar.Avatar
import com.intellij.collaboration.ui.codereview.list.search.ChooserPopupUtil
import com.intellij.collaboration.ui.codereview.list.search.DropDownComponentFactory
import com.intellij.collaboration.ui.codereview.list.search.ReviewListSearchPanelFactory
import com.intellij.collaboration.ui.util.popup.PopupItemPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flow
import javax.swing.JComponent

/**
 * Search bar with quick filters and filter drop-downs, the Gitea
 * counterpart of the GitHub plugin's `GHPRSearchPanelFactory`. Strings are
 * the GitHub plugin's (`pull.request.list.filter.*`).
 */
class GiteaSearchPanelFactory(
    vm: GiteaSearchPanelViewModel,
    private val avatars: GiteaAvatarIcons,
) : ReviewListSearchPanelFactory<GiteaListSearchValue, GiteaListQuickFilter, GiteaSearchPanelViewModel>(vm) {

    override fun getShortText(searchValue: GiteaListSearchValue): String = with(searchValue) {
        buildString {
            if (searchQuery != null) append("\"$searchQuery\" ")
            if (state != null) append("state:\"${stateText(state)}\" ")
            if (label != null) append("label:${label.name} ")
            if (assignee != null) append("assignee:$assignee ")
            if (reviewRequested == true) append("review-requested ")
            if (author != null) append("author:$author ")
            if (sort != null) append("sort:\"${sortText(sort)}\" ")
        }
    }

    override fun createFilters(viewScope: CoroutineScope): List<JComponent> = listOf(
        DropDownComponentFactory(vm.stateFilterState).create(
            viewScope,
            filterName = "State",
            items = GiteaListSearchValue.State.entries,
            onSelect = {},
            valuePresenter = ::stateText,
        ),
        DropDownComponentFactory(vm.authorFilterState).create(viewScope, "Author") { point ->
            ChooserPopupUtil.showAsyncChooserPopup(
                point,
                itemsLoader = flow { emit(runCatchingCancellable { vm.getUsers() }) },
                presenter = { user ->
                    PopupItemPresentation.Simple(
                        user.login,
                        avatars.getIcon(user, Avatar.Sizes.BASE),
                        user.fullName?.takeIf { it.isNotBlank() }?.let { "($it)" },
                    )
                },
            )?.login
        },
        DropDownComponentFactory(vm.labelFilterState).create(viewScope, "Label", valuePresenter = { it.name }) { point ->
            ChooserPopupUtil.showAsyncChooserPopup(
                point,
                itemsLoader = flow { emit(runCatchingCancellable { vm.getLabels() }) },
                presenter = { label -> PopupItemPresentation.Simple(label.name) },
            )
        },
        DropDownComponentFactory(vm.assigneeFilterState).create(viewScope, "Assignee") { point ->
            ChooserPopupUtil.showAsyncChooserPopup(
                point,
                itemsLoader = flow { emit(runCatchingCancellable { vm.getUsers() }) },
                presenter = { user ->
                    PopupItemPresentation.Simple(
                        user.login,
                        avatars.getIcon(user, Avatar.Sizes.BASE),
                        user.fullName?.takeIf { it.isNotBlank() }?.let { "($it)" },
                    )
                },
            )?.login
        },
        DropDownComponentFactory(vm.sortFilterState).create(
            viewScope,
            filterName = "Sort",
            items = GiteaListSearchValue.Sort.entries,
            onSelect = {},
            valuePresenter = ::sortText,
        ),
    )

    override fun GiteaListQuickFilter.getQuickFilterTitle(): String = when (this) {
        is GiteaListQuickFilter.Open -> "Open"
        is GiteaListQuickFilter.YourPullRequests -> "Your pull requests"
        is GiteaListQuickFilter.AssignedToYou -> "Assigned to you"
        is GiteaListQuickFilter.ReviewRequests -> "Review requests"
    }

    companion object {
        fun stateText(state: GiteaListSearchValue.State): String = when (state) {
            GiteaListSearchValue.State.OPEN -> "Open"
            GiteaListSearchValue.State.CLOSED -> "Closed"
            GiteaListSearchValue.State.MERGED -> "Merged"
        }

        fun sortText(sort: GiteaListSearchValue.Sort): String = when (sort) {
            GiteaListSearchValue.Sort.NEWEST -> "Newest"
            GiteaListSearchValue.Sort.OLDEST -> "Oldest"
            GiteaListSearchValue.Sort.MOST_COMMENTED -> "Most commented"
            GiteaListSearchValue.Sort.LEAST_COMMENTED -> "Least commented"
            GiteaListSearchValue.Sort.RECENTLY_UPDATED -> "Recently updated"
            GiteaListSearchValue.Sort.LEAST_RECENTLY_UPDATED -> "Least recently updated"
        }
    }
}
