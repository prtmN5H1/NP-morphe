/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.system

import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import app.morphe.manager.ui.screen.shared.ConfirmDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.morphe.manager.ui.screen.shared.DialogHost
import app.morphe.manager.ui.viewmodel.InstallViewModel

/**
 * Renders the installer-selection and installer-unavailable dialogs driven by
 * [InstallViewModel] state. Called from any screen that triggers an install
 * ([app.morphe.manager.ui.screen.HomeScreen], [app.morphe.manager.ui.screen.PatcherScreen],
 * [ApkManagementPage], etc.).
 *
 * Lives in this package because the two dialogs it composes are defined here.
 */
@Composable
fun InstallerFlowDialogs(
    installViewModel: InstallViewModel
) {
    DialogHost(target = installViewModel.installerUnavailableDialog) { dialogState ->
        InstallerUnavailableDialog(
            state = dialogState,
            onOpenApp = installViewModel::openInstallerApp,
            onRetry = installViewModel::retryWithPreferredInstaller,
            onChooseAnother = installViewModel::chooseAnotherInstaller,
            onDismiss = installViewModel::dismissInstallerUnavailableDialog
        )
    }

    DialogHost(visible = installViewModel.showInstallerSelectionDialog) {
        val selected = remember { installViewModel.getPrimaryInstallerToken() }
        val options = rememberPolledInstallerEntries(selected, installViewModel::getInstallerOptions)
        InstallerSelectionDialog(
            options = options,
            selected = selected,
            onDismiss = installViewModel::dismissInstallerSelectionDialog,
            onConfirm = installViewModel::proceedWithSelectedInstaller,
            onOpenShizuku = installViewModel::openShizukuApp,
            shizukuStatusProvider = installViewModel::getShizukuStatus,
            onRequestShizukuPermission = installViewModel::requestShizukuPermission
        )
    }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        installViewModel.onManagerResumed()
    }

    DialogHost(target = installViewModel.rollbackCleanupPromptPackage) { (appName, packageName) ->
        ConfirmDialog(
            title = stringResource(R.string.installer_rollback_verify_title),
            message = stringResource(R.string.installer_rollback_verify_message, appName),
            primaryText = stringResource(R.string.installer_rollback_clear_backup),
            onConfirm = { installViewModel.clearRollbackBackup(packageName) },
            onDismiss = installViewModel::dismissRollbackCleanupPrompt
        )
    }
}
