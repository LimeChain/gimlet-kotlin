package com.limechain.gimlet

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.GimletBundle"

/**
 * All user-visible strings in the plugin route through here so they can
 * be localised later without touching call sites. Annotated with
 * `@PropertyKey(resourceBundle = BUNDLE)` so the IDE's inspection flags
 * keys that don't exist in the `.properties` file at authoring time.
 *
 * Usage:
 *   GimletBundle.message("settings.title")
 *   GimletBundle.message("error.missingSymbols.body", programName)
 */
internal object GimletBundle : DynamicBundle(BUNDLE) {
    @Nls
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = getMessage(key, *params)
}
