package com.github.hypercookie.giteaplugin.util

import com.intellij.collaboration.ui.codereview.list.error.ErrorStatusPanelFactory
import com.intellij.collaboration.ui.codereview.list.error.ErrorStatusPresenter
import javax.swing.JComponent

/** Text shown under an error title; falls back to the exception type for
 * errors without a message (e.g. some `IOException`s). */
fun errorDescription(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

/** Presenter with a fixed [title] and the error's message as description. */
fun errorPresenter(title: String): ErrorStatusPresenter.Text<Throwable> =
    ErrorStatusPresenter.simple(title, descriptionProvider = ::errorDescription)

/**
 * A static error panel (icon, title, description) that wraps long messages
 * over several lines instead of truncating them, as the platform's review
 * popups and lists do.
 */
fun errorPanel(title: String, error: Throwable): JComponent =
    ErrorStatusPanelFactory.create(error, errorPresenter(title), ErrorStatusPanelFactory.Alignment.LEFT)
