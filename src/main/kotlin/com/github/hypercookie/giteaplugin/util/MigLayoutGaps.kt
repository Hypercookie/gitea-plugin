package com.github.hypercookie.giteaplugin.util

import net.miginfocom.layout.CC
import java.awt.Insets

/** `CC.gap(Insets)`, mirrored from the collaboration tools'
 * `migLayoutUtil.kt`, which is `@ApiStatus.Internal` (migration ledger I12). */
fun CC.gap(insets: Insets): CC = gap("${insets.left}", "${insets.right}", "${insets.top}", "${insets.bottom}")
