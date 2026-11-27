/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.sources

import androidx.annotation.StringRes
import app.morphe.manager.R
import app.morphe.manager.domain.bundles.PatchBundleSource
import app.morphe.manager.domain.bundles.PatchBundleSource.Extensions.isHeldBack
import app.morphe.manager.domain.manager.SortModeSpec

/** Which sources the sources page lists. */
enum class SourceFilterMode(
    @param:StringRes override val labelRes: Int
) : SortModeSpec {
    ALL(R.string.all),
    ENABLED(R.string.enabled),
    DISABLED(R.string.disabled),
    UPDATE_OFF(R.string.npmorphe_source_filter_update_off),
    LOADING_PAUSED(R.string.npmorphe_source_filter_loading_pause),
    SELECTED_APP(R.string.npmorphe_source_filter_selected_app),
    LOADING_DISABLED(R.string.npmorphe_source_filter_loading_disabled),
    LOADING_ENABLED(R.string.npmorphe_source_filter_loading_enabled),
    ISSUES(R.string.sources_filter_issues);

    val isActive: Boolean get() = this != ALL

    /**
     * @param blocked Whether the source is blocked, which leaves it unusable however it is set.
     * @param unreachable Whether its details could not be fetched.
     */
    fun matches(
        source: PatchBundleSource,
        blocked: Boolean,
        unreachable: Boolean,
        updateOff: Boolean,
        loadingPaused: Boolean,
        hasDeselectedApps: Boolean
    ): Boolean =
        when (this) {
            ALL -> true
            ENABLED -> source.enabled && !blocked
            DISABLED -> !source.enabled
            UPDATE_OFF -> updateOff
            LOADING_PAUSED -> loadingPaused
            SELECTED_APP -> hasDeselectedApps
            LOADING_DISABLED -> loadingPaused
            LOADING_ENABLED -> !loadingPaused
            ISSUES -> blocked || unreachable || source.isHeldBack || source.requiresManagerUpdate ||
                    source.state is PatchBundleSource.State.Failed ||
                    (source.state is PatchBundleSource.State.Missing && !loadingPaused)
        }
}
