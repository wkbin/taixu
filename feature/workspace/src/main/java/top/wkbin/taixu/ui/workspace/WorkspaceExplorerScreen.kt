package top.wkbin.taixu.ui.workspace

import top.wkbin.taixu.ui.components.RuntimeAlertDialog

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import top.wkbin.taixu.ui.components.RuntimeButton as Button
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import top.wkbin.taixu.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.wkbin.taixu.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import top.wkbin.taixu.feature.workspace.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.runtime.WorkspaceFileItem
import top.wkbin.taixu.ui.components.EmptyPanel
import top.wkbin.taixu.ui.components.NoticeBanner
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 太墟 · 文件浏览器 (File Explorer)
 * 实时树形遍历与文件 CRUD
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceExplorerScreen(
    projectName: String,
    initialPath: String = "",
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    viewModel: WorkspaceViewModel = hiltViewModel(),
) {
    val fileItems by viewModel.fileItems.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentPath.collectAsStateWithLifecycle()
    val loading by viewModel.loadingFiles.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val sharedStorageAccessLimited by viewModel.sharedStorageAccessLimited.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val allFilesPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // 从系统"所有文件访问"授权页返回（无论是否授权）都重新评估并刷新
        viewModel.refreshAfterPermissionReturn()
    }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshAfterPermissionReturn()
    }

    var showCreateMenu by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<WorkspaceFileItem?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileItem?>(null) }

    var newFileName by remember { mutableStateOf("") }
    var newFolderName by remember { mutableStateOf("") }
    var renameInput by remember { mutableStateOf("") }

    LaunchedEffect(projectName, initialPath) {
        viewModel.loadExplorer(projectName, initialPath)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = projectName,
                statusText = if (currentPath.isBlank()) "/workspace/$projectName" else ".../$currentPath",
                onBack = {
                    if (currentPath.isNotBlank()) {
                        viewModel.navigateUp()
                    } else {
                        onBack()
                    }
                },
                actions = {
                    // 新建按钮菜单
                    Box {
                        IconButton(onClick = { showCreateMenu = true }, enabled = !busy) {
                            RuntimeIcon(RuntimeIconName.Plus, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(
                            expanded = showCreateMenu,
                            onDismissRequest = { showCreateMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_new_file)) },
                                leadingIcon = { RuntimeIcon(RuntimeIconName.File, Modifier.size(18.dp)) },
                                onClick = {
                                    showCreateMenu = false
                                    newFileName = ""
                                    showCreateFileDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_new_folder)) },
                                leadingIcon = { RuntimeIcon(RuntimeIconName.Folder, Modifier.size(18.dp)) },
                                onClick = {
                                    showCreateMenu = false
                                    newFolderName = ""
                                    showCreateFolderDialog = true
                                },
                            )
                        }
                    }

                    // 终端入口
                    IconButton(
                        onClick = { onOpenTerminal(projectName) },
                    ) {
                        RuntimeIcon(RuntimeIconName.Terminal, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }

                    // 刷新
                    IconButton(onClick = { viewModel.refreshDirectory() }, enabled = !loading) {
                        RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 面包屑导航栏
            BreadcrumbBar(
                projectName = projectName,
                currentPath = currentPath,
                onNavigate = { targetPath -> viewModel.navigateToDirectory(targetPath) },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 共享存储"所有文件访问"权限缺失横幅：系统会过滤其他应用的文件（只显示文件夹）
            if (sharedStorageAccessLimited) {
                SharedStoragePermissionBanner(
                    onAuthorize = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            allFilesPermissionLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        } else {
                            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    },
                )
            }

            // 消息提示
            message?.let { notice ->
                Box(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    NoticeBanner(
                        text = notice,
                        isError = notice.contains("失败") || notice.contains("错误") || notice.contains("存在"),
                    )
                }
            }

            if (loading && fileItems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(36.dp), color = Color(0xFF00F0FF))
                }
            } else if (fileItems.isEmpty()) {
                EmptyPanel(
                    icon = RuntimeIconName.Folder,
                    title = stringResource(R.string.workspace_empty_directory),
                    description = stringResource(R.string.workspace_empty_directory_hint),
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 如果在子目录，显示返回上一级项
                    if (currentPath.isNotBlank()) {
                        item {
                            ParentDirectoryRow(onClick = { viewModel.navigateUp() })
                            HorizontalDivider(color = Color(0xFF172338))
                        }
                    }

                    items(fileItems, key = { it.relativePath }) { item ->
                        FileItemRow(
                            item = item,
                            onClick = {
                                if (item.isDirectory) {
                                    viewModel.navigateToDirectory(item.relativePath)
                                } else {
                                    onOpenFile(item.relativePath)
                                }
                            },
                            onRename = {
                                renameTarget = item
                                renameInput = item.name
                            },
                            onDelete = {
                                deleteTarget = item
                            },
                        )
                        HorizontalDivider(color = Color(0xFF141F33))
                    }
                }
            }
        }
    }

    // 新建文件对话框
    if (showCreateFileDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showCreateFileDialog = false },
            title = { Text(stringResource(R.string.workspace_new_file), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text(stringResource(R.string.workspace_file_name)) },
                        placeholder = { Text("main.py / app.js / config.json") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createFile(newFileName)
                        showCreateFileDialog = false
                    },
                    enabled = newFileName.isNotBlank() && !busy,
                ) { Text(stringResource(R.string.workspace_create), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { showCreateFileDialog = false }) { Text(stringResource(R.string.workspace_cancel)) } },
        )
    }

    // 新建文件夹对话框
    if (showCreateFolderDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text(stringResource(R.string.workspace_new_folder), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(stringResource(R.string.workspace_folder_name)) },
                    placeholder = { Text("src / models / tests") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createDirectory(newFolderName)
                        showCreateFolderDialog = false
                    },
                    enabled = newFolderName.isNotBlank() && !busy,
                ) { Text(stringResource(R.string.workspace_create), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { showCreateFolderDialog = false }) { Text(stringResource(R.string.workspace_cancel)) } },
        )
    }

    // 重命名对话框
    renameTarget?.let { target ->
        RuntimeAlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.workspace_rename_title, target.name), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.workspace_new_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameItem(target.relativePath, renameInput)
                        renameTarget = null
                    },
                    enabled = renameInput.isNotBlank() && renameInput != target.name && !busy,
                ) { Text(stringResource(R.string.workspace_save), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.workspace_cancel)) } },
        )
    }

    // 删除确认对话框
    deleteTarget?.let { target ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.workspace_delete_title, target.name), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(if (target.isDirectory) R.string.workspace_delete_folder_message else R.string.workspace_delete_file_message),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteItem(target.relativePath)
                        deleteTarget = null
                    },
                    enabled = !busy,
                ) { Text(stringResource(R.string.workspace_confirm_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.workspace_cancel)) } },
        )
    }
}

@Composable
private fun BreadcrumbBar(
    projectName: String,
    currentPath: String,
    onNavigate: (String) -> Unit,
) {
    val segments = currentPath.split('/').filter { it.isNotBlank() }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                BreadcrumbChip(
                    label = projectName,
                    icon = RuntimeIconName.Workspace,
                    isCurrent = segments.isEmpty(),
                    onClick = { onNavigate("") },
                )
            }
            items(segments.size) { index ->
                val segment = segments[index]
                val pathSoFar = segments.take(index + 1).joinToString("/")
                val isLast = index == segments.size - 1

                RuntimeIcon(
                    RuntimeIconName.ChevronRight,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BreadcrumbChip(
                    label = segment,
                    icon = RuntimeIconName.Folder,
                    isCurrent = isLast,
                    onClick = { onNavigate(pathSoFar) },
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbChip(
    label: String,
    icon: RuntimeIconName,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            RuntimeIcon(
                icon,
                Modifier.size(14.dp),
                tint = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace,
                ),
                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SharedStoragePermissionBanner(onAuthorize: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RuntimeIcon(
                RuntimeIconName.Alert,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.workspace_shared_permission_banner_title),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.workspace_shared_permission_banner_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
            TextButton(onClick = onAuthorize) {
                Text(
                    text = stringResource(R.string.workspace_shared_permission_go_settings),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ParentDirectoryRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RuntimeIcon(
            RuntimeIconName.ArrowUp,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.workspace_parent_directory),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun FileItemRow(
    item: WorkspaceFileItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val extColor = fileExtensionColor(item.extension)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                onClick()
            })
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 图标
        if (item.isDirectory) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                RuntimeIcon(
                    RuntimeIconName.Folder,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(extColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                RuntimeIcon(
                    if (isCodeExtension(item.extension)) RuntimeIconName.Code else RuntimeIconName.File,
                    Modifier.size(18.dp),
                    tint = extColor,
                )
            }
        }

        // 名称和详情
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!item.isDirectory && item.extension.isNotBlank()) {
                    Surface(
                        color = extColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = item.extension.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = extColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!item.isDirectory) {
                    Text(
                        text = item.sizeBytes.toReadableSize(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.lastModified > 0) {
                    Text(
                        text = formatTime(item.lastModified),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 操作菜单
        Box {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    showMenu = true
                },
                modifier = Modifier.size(32.dp),
            ) {
                RuntimeIcon(
                    RuntimeIconName.More,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workspace_rename)) },
                    leadingIcon = { RuntimeIcon(RuntimeIconName.Edit, Modifier.size(16.dp)) },
                    onClick = {
                        showMenu = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workspace_delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        RuntimeIcon(
                            RuntimeIconName.Trash,
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

private fun fileExtensionColor(ext: String): Color = when (ext.lowercase()) {
    "kt", "kts", "java" -> Color(0xFF7F52FF)
    "py" -> Color(0xFF3776AB)
    "rs" -> Color(0xFFE43716)
    "c", "cpp", "h", "hpp" -> Color(0xFF00897B)
    "sh", "bash", "zsh" -> Color(0xFF43A047)
    "js", "ts", "jsx", "tsx" -> Color(0xFFE08600)
    "json", "yaml", "yml", "toml", "xml" -> Color(0xFFFB8C00)
    "md", "markdown" -> Color(0xFF3949AB)
    "html", "css" -> Color(0xFFE53935)
    "sql", "db" -> Color(0xFF00ACC1)
    else -> Color(0xFF8E9099)
}

private fun isCodeExtension(ext: String): Boolean = ext in setOf(
    "py", "js", "ts", "jsx", "tsx", "kt", "kts", "java", "c", "cpp", "h", "hpp",
    "rs", "go", "sh", "bash", "json", "yaml", "yml", "toml", "md", "html", "css", "sql",
)

private fun formatTime(millis: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

private fun Long.toReadableSize(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "%.1f KB".format(this / 1024.0)
    this < 1024 * 1024 * 1024 -> "%.1f MB".format(this / (1024.0 * 1024))
    else -> "%.1f GB".format(this / (1024.0 * 1024 * 1024))
}
