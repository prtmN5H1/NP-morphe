# Upstream sync review: `v2.7.0-dev.6`

The tag was fetched from `MorpheApp/morphe-manager` and compared with fork `main` at `9be6eb9c6df80962249b537e4ea87ee493b23c01`. A read-only `git merge-tree` check found 22 conflicted paths. The upstream tag overlaps the fork's custom updater, source management, patch/install flow, settings, and credits; the release metadata also differs, and upstream deletes the custom credits dialog. This is not a safe automatic merge, so no upstream code was applied and fork `main` remains unchanged.

Conflicted paths:

- `app-release.json`
- `app/src/main/java/app/morphe/manager/MainActivity.kt`
- `app/src/main/java/app/morphe/manager/domain/installer/SessionInstaller.kt`
- `app/src/main/java/app/morphe/manager/domain/manager/PreferencesManager.kt`
- `app/src/main/java/app/morphe/manager/domain/manager/SourceBundleSortMode.kt`
- `app/src/main/java/app/morphe/manager/domain/repository/PatchBundleRepository.kt`
- `app/src/main/java/app/morphe/manager/domain/repository/StorageStatsRepository.kt`
- `app/src/main/java/app/morphe/manager/network/api/ReleaseAPI.kt`
- `app/src/main/java/app/morphe/manager/patcher/worker/PatcherWorker.kt`
- `app/src/main/java/app/morphe/manager/ui/screen/BatchPatcherScreen.kt`
- `app/src/main/java/app/morphe/manager/ui/screen/settings/system/AboutSettings.kt`
- `app/src/main/java/app/morphe/manager/ui/screen/settings/system/ApkDownloadHelpersSettings.kt`
- `app/src/main/java/app/morphe/manager/ui/screen/settings/system/CreditsDialog.kt` (upstream deletes this fork file)
- `app/src/main/java/app/morphe/manager/ui/screen/shared/FilePicker.kt`
- `app/src/main/java/app/morphe/manager/ui/viewmodel/AboutViewModel.kt`
- `app/src/main/java/app/morphe/manager/ui/viewmodel/BatchPatcherViewModel.kt`
- `app/src/main/java/app/morphe/manager/ui/viewmodel/ImportExportViewModel.kt`
- `app/src/main/java/app/morphe/manager/ui/viewmodel/InstallViewModel.kt`
- `app/src/main/java/app/morphe/manager/ui/viewmodel/MainViewModel.kt`
- `app/src/main/java/app/morphe/manager/ui/viewmodel/PatchFlow.kt`
- `app/src/main/java/app/morphe/manager/util/Constants.kt`
- `app/src/main/res/values/strings.xml`

The automated sync marker is set to `v2.7.0-dev.6`. Scheduled runs will defer this exact tag instead of repeatedly failing. A manual run with `sync_upstream=true` retries it; `force_build=true` with `sync_upstream=false` builds the current fork main without merging upstream.
