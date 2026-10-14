package com.github.hypercookie.giteaplugin.pullrequest.review.mention

import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaAvatarIcons
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.collaboration.ui.codereview.avatar.Avatar
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * `@` completion of Gitea users in comment fields. Same mechanism as the
 * GitHub and GitLab plugins' mention completion (added to intellij-community
 * after 2025.2): a contributor registered for any language that only acts
 * in editors carrying a [GiteaMentionSource].
 */
class GiteaMentionCompletionContributor : CompletionContributor(), DumbAware {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val source = parameters.editor.getUserData(GiteaMentionSource.KEY) ?: return
        val before = parameters.editor.document.immutableCharSequence.subSequence(0, parameters.offset)
        val prefix = GiteaMentions.mentionPrefix(before) ?: return

        val mentionResult = result.withPrefixMatcher(prefix).caseInsensitive()
        val project = parameters.editor.project
        val candidates = try {
            runBlockingCancellable { source.candidates() }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn("Failed to load users for mention completion", e)
            return
        }
        mentionResult.addAllElements(candidates.map { lookupElement(it, project) })
        // Other contributors (words, Markdown) have nothing useful to add
        // inside a mention.
        result.stopHere()
    }

    private fun lookupElement(candidate: GiteaMentionCandidate, project: Project?): LookupElement {
        val user = candidate.user
        val fullName = user.fullName?.takeIf { it.isNotBlank() }
        var builder = LookupElementBuilder.create(user, "@${user.login}")
            .withPresentableText("@${user.login}")
            .withTailText(fullName?.let { " $it" }, true)
            .withInsertHandler { context, _ ->
                val offset = context.selectionEndOffset
                val text = context.document.charsSequence
                if (offset == text.length || text[offset] != ' ') {
                    context.document.insertString(offset, " ")
                }
                context.editor.caretModel.moveToOffset(offset + 1)
            }
        if (project != null) builder = builder.withIcon(GiteaAvatarIcons.getInstance(project).getIcon(user, Avatar.Sizes.SMALL))
        if (fullName != null) builder = builder.withLookupString("@$fullName")
        return PrioritizedLookupElement.withPriority(builder, candidate.priority)
    }
}

/** Opens the completion popup when `@` is typed in a comment field. */
class GiteaMentionTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped == '@' && editor.getUserData(GiteaMentionSource.KEY) != null) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        }
        return Result.CONTINUE
    }
}
