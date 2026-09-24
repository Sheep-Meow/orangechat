/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.Square
import me.rerere.hugeicons.stroke.Terminal
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.LocalNavController
import me.rerere.rikkahub.ui.LocalToaster
import me.rerere.rikkahub.ui.components.CustomColors
import me.rerere.rikkahub.ui.components.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.navigation.Screen
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import java.io.InputStream
import java.util.Locale

@Composable
fun WorkspaceDetailPage(
    id: String,
    vm: WorkspaceDetailVM = koinViewModel(),
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { 2 }

    LaunchedEffect(id) {
        vm.loadWorkspace(id)
    }

    var showInstallDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIdx >= 0) cursor.getString(nameIdx) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "imported_file"

            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                vm.importFile(fileName, inputStream)
            } else {
                toaster.show(context.getString(R.string.workspace_detail_import_failed))
            }
        }
    }

    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        val target = exportTarget
        if (uri != null && target != null) {
            context.contentResolver.openOutputStream(uri)?.let { out ->
                vm.exportFile(target, out)
            } ?: toaster.show(context.getString(R.string.workspace_detail_export_failed))
        }
        exportTarget = null
    }

    val openFileDirectly: (WorkspaceFileEntry) -> Unit = { entry ->
        vm.shareFile(entry, context.cacheDir) { file ->
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            val mimeType = when (ext) {
                "txt", "log", "json", "md", "py", "kt", "sh", "yaml", "yml", "xml", "csv" -> "text/plain"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "m4a", "aac" -> "audio/aac"
                "ogg", "opus" -> "audio/ogg"
                "flac" -> "audio/flac"
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "3gp" -> "video/3gpp"
                "pdf" -> "application/pdf"
                else -> "*/*"
            }
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(Intent.createChooser(viewIntent, entry.name))
            }.onFailure {
                toaster.show("打开文件失败，请尝试使用导出功能")
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(state.workspace?.name ?: stringResource(R.string.workspace_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshFiles() }) {
                        Icon(HugeIcons.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    if (state.shellStatus == WorkspaceShellStatus.RUNNING) {
                        IconButton(
                            onClick = { navController.navigate(Screen.WorkspaceTerminal(id)) }
                        ) {
                            Icon(HugeIcons.Terminal, contentDescription = stringResource(R.string.workspace_detail_terminal))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(HugeIcons.Square, contentDescription = null) },
                    label = { Text(stringResource(R.string.workspace_detail_tab_basic)) },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(HugeIcons.Folder01, contentDescription = null) },
                    label = { Text(stringResource(R.string.workspace_detail_tab_files)) },
                )
            }
        },
        floatingActionButton = {
            if (pagerState.currentPage == 1) {
                FloatingActionButton(onClick = { importFileLauncher.launch(arrayOf("*/*")) }) {
                    Icon(HugeIcons.PlusSign, contentDescription = stringResource(R.string.workspace_detail_import_file))
                }
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { page ->
            when (page) {
                0 -> WorkspaceBasicPage(
                    state = state,
                    approvals = vm.toolApprovals(state.workspace),
                    onInstallRootfs = { showInstallDialog = true },
                    onCancelInstall = { vm.cancelInstall() },
                    onToolApprovalChange = { name, needsApproval -> vm.setToolApproval(name, needsApproval) },
                )

                1 -> WorkspaceFilesPage(
                    state = state,
                    onSwitchArea = { vm.switchArea(it) },
                    onNavigateUp = { vm.navigateUp() },
                    onOpen = { vm.navigateTo(it.path) },
                    onOpenFile = openFileDirectly,
                    onDelete = { deleteTarget = it },
                    onExport = { entry ->
                        exportTarget = entry
                        exportFileLauncher.launch(entry.name)
                    },
                    onShare = { entry ->
                        vm.shareFile(entry, context.cacheDir) { file ->
                            val shareUri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, shareUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    },
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.workspace_detail_delete_file_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.deleteFile(it) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.workspace_detail_delete_file_message, deleteTarget?.name ?: ""))
    }

    RootfsInstallUrlDialog(
        show = showInstallDialog,
        onDismiss = { showInstallDialog = false },
        onConfirm = { url ->
            showInstallDialog = false
            vm.installRootfs(url)
        },
    )
}

@Composable
private fun WorkspaceBasicPage(
    state: WorkspaceDetailState,
    approvals: Map<String, Boolean>,
    onInstallRootfs: () -> Unit,
    onCancelInstall: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            RootfsCard(
                status = state.shellStatus,
                installing = state.installing,
                progressStageText = installProgressText(state),
                onInstall = onInstallRootfs,
                onCancel = onCancelInstall,
            )
        }

        item {
            ToolApprovalCard(
                approvals = approvals,
                onChange = onToolApprovalChange,
            )
        }
    }
}

@Composable
private fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    onSwitchArea: (WorkspaceStorageArea) -> Unit,
    onNavigateUp: () -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onOpenFile: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AreaSelector(
                area = state.area,
                onSwitch = onSwitchArea,
            )
        }

        item {
            PathBar(
                path = state.currentPath,
                canGoUp = state.currentPath.isNotBlank(),
                onGoUp = onNavigateUp,
            )
        }

        if (state.filesLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (state.files.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.workspace_detail_empty_dir),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            val hideDelete = state.area == WorkspaceStorageArea.LINUX && state.currentPath.isBlank()
            items(state.files, key = { it.path }) { entry ->
                FileRow(
                    entry = entry,
                    onClick = {
                        if (entry.isDirectory) {
                            onOpen(entry)
                        } else {
                            onOpenFile(entry)
                        }
                    },
                    onDelete = { onDelete(entry) },
                    onExport = { onExport(entry) },
                    onShare = { onShare(entry) },
                    showDelete = !hideDelete,
                )
            }
        }
    }
}

@Composable
private fun RootfsInstallUrlDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by remember(show) { mutableStateOf("https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz") }
    var error by remember(show) { mutableStateOf(false) }

    RikkaConfirmDialog(
        show = show,
        title = stringResource(R.string.workspace_detail_install_rootfs),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            if (url.isBlank()) {
                error = true
            } else {
                onConfirm(url.trim())
            }
        },
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    error = false
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = error,
                placeholder = {
                    Text(stringResource(R.string.workspace_detail_install_url_hint))
                },
                supportingText = {
                    if (error) {
                        Text(stringResource(R.string.workspace_detail_install_url_empty))
                    }
                },
            )
        }
    }
}

@Composable
private fun RootfsCard(
    status: WorkspaceShellStatus,
    installing: Boolean,
    progressStageText: String?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_detail_rootfs_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(workspaceStatusLabel(status)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (installing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (progressStageText != null) {
                    Text(
                        text = progressStageText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installing) {
                    OutlinedButton(onClick = onCancel) {
                        Text(stringResource(R.string.cancel))
                    }
                } else {
                    Button(onClick = onInstall) {
                        Text(
                            stringResource(
                                if (status == WorkspaceShellStatus.STOPPED) {
                                    R.string.workspace_detail_install
                                } else {
                                    R.string.workspace_detail_reinstall
                                }
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolApprovalCard(
    approvals: Map<String, Boolean>,
    onChange: (String, Boolean) -> Unit,
) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_detail_tool_approvals_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.workspace_detail_tool_approvals_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            approvals.forEach { (name, needsApproval) ->
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text(workspaceToolLabel(name)) },
                    tail = {
                        Switch(
                            checked = needsApproval,
                            onCheckedChange = { onChange(name, it) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun AreaSelector(
    area: WorkspaceStorageArea,
    onSwitch: (WorkspaceStorageArea) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = area == WorkspaceStorageArea.FILES,
            onClick = { onSwitch(WorkspaceStorageArea.FILES) },
            label = { Text(stringResource(R.string.workspace_detail_area_files)) },
        )
        FilterChip(
            selected = area == WorkspaceStorageArea.LINUX,
            onClick = { onSwitch(WorkspaceStorageArea.LINUX) },
            label = { Text(stringResource(R.string.workspace_detail_area_linux)) },
        )
    }
}

@Composable
private fun PathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canGoUp) {
            IconButton(onClick = onGoUp) {
                Icon(HugeIcons.ArrowLeft01, contentDescription = null)
            }
        }
        Text(
            text = "/" + path,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileRow(
    entry: WorkspaceFileEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit = {},
    onShare: () -> Unit = {},
    showDelete: Boolean = true,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File01,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!entry.isDirectory) {
                    Text(
                        text = formatSize(entry.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row {
                if (!entry.isDirectory) {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(HugeIcons.MoreVertical, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_detail_export)) },
                            leadingIcon = { Icon(HugeIcons.Download01, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_detail_share)) },
                            leadingIcon = { Icon(HugeIcons.Share01, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onShare()
                            },
                        )
                    }
                }
                if (showDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun installProgressText(state: WorkspaceDetailState): String? {
    val stage = state.installProgress ?: return null
    return stage.name.lowercase(Locale.getDefault())
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.getDefault(), "%.1f MB", mb)
}

private fun workspaceStatusLabel(status: WorkspaceShellStatus): Int = when (status) {
    WorkspaceShellStatus.STOPPED -> R.string.workspace_status_stopped
    WorkspaceShellStatus.INSTALLING -> R.string.workspace_status_installing
    WorkspaceShellStatus.STARTING -> R.string.workspace_status_starting
    WorkspaceShellStatus.RUNNING -> R.string.workspace_status_running
    WorkspaceShellStatus.FAILED -> R.string.workspace_status_failed
}

private fun workspaceToolLabel(name: String): String = when (name) {
    "workspace_read_file" -> "读取文件"
    "workspace_write_file" -> "写入文件"
    "workspace_edit_file" -> "编辑文件"
    "workspace_shell" -> "执行命令"
    else -> name
}
