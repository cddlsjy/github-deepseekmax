package com.gemini.githubexplorer.ui.main

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gemini.githubexplorer.data.github.Artifact
import com.gemini.githubexplorer.data.github.GitHubRepo
import com.gemini.githubexplorer.data.github.Workflow
import com.gemini.githubexplorer.data.github.WorkflowRun
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val currentContext = LocalContext.current as Activity

    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val showSnackbar by viewModel.showSnackbar.collectAsStateWithLifecycle()
    val showDialog by viewModel.showDialog.collectAsStateWithLifecycle()

    val fontScale by viewModel.fontScale
    val originalTypography = MaterialTheme.typography
    val scaledTypography = remember(fontScale) {
        Typography(
            displayLarge = originalTypography.displayLarge.copy(fontSize = originalTypography.displayLarge.fontSize * fontScale),
            displayMedium = originalTypography.displayMedium.copy(fontSize = originalTypography.displayMedium.fontSize * fontScale),
            displaySmall = originalTypography.displaySmall.copy(fontSize = originalTypography.displaySmall.fontSize * fontScale),
            headlineLarge = originalTypography.headlineLarge.copy(fontSize = originalTypography.headlineLarge.fontSize * fontScale),
            headlineMedium = originalTypography.headlineMedium.copy(fontSize = originalTypography.headlineMedium.fontSize * fontScale),
            headlineSmall = originalTypography.headlineSmall.copy(fontSize = originalTypography.headlineSmall.fontSize * fontScale),
            titleLarge = originalTypography.titleLarge.copy(fontSize = originalTypography.titleLarge.fontSize * fontScale),
            titleMedium = originalTypography.titleMedium.copy(fontSize = originalTypography.titleMedium.fontSize * fontScale),
            titleSmall = originalTypography.titleSmall.copy(fontSize = originalTypography.titleSmall.fontSize * fontScale),
            bodyLarge = originalTypography.bodyLarge.copy(fontSize = originalTypography.bodyLarge.fontSize * fontScale),
            bodyMedium = originalTypography.bodyMedium.copy(fontSize = originalTypography.bodyMedium.fontSize * fontScale),
            bodySmall = originalTypography.bodySmall.copy(fontSize = originalTypography.bodySmall.fontSize * fontScale),
            labelLarge = originalTypography.labelLarge.copy(fontSize = originalTypography.labelLarge.fontSize * fontScale),
            labelMedium = originalTypography.labelMedium.copy(fontSize = originalTypography.labelMedium.fontSize * fontScale),
            labelSmall = originalTypography.labelSmall.copy(fontSize = originalTypography.labelSmall.fontSize * fontScale),
        )
    }

    LaunchedEffect(showSnackbar) {
        showSnackbar?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            viewModel.dismissSnackbar()
        }
    }

    if (showDialog != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissAlertDialog() },
            title = { Text("Information") },
            text = { Text(showDialog!!.toString()) },
            confirmButton = {
                Button(onClick = { viewModel.dismissAlertDialog() }) { Text("OK") }
            }
        )
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri: Uri? ->
            uri?.let {
                viewModel.repoDownloadUrl.value?.let { downloadUrl ->
                    scope.launch { viewModel.performRepoDownload(it, downloadUrl) }
                } ?: viewModel.artifactDownloadUrl.value?.let { downloadUrl ->
                    scope.launch { viewModel.performArtifactDownload(it, downloadUrl) }
                }
            } ?: viewModel.showSnackbarMessage("Download cancelled.")
            // Reset download states
            viewModel.repoDownloadUrl.value = null
            viewModel.repoDownloadFileName.value = null
            viewModel.artifactDownloadUrl.value = null
            viewModel.artifactFileName.value = null
        }
    )

    // Launch download when repo download URL is ready
    LaunchedEffect(viewModel.repoDownloadUrl.value) {
        viewModel.repoDownloadUrl.value?.let { url ->
            val fileName = viewModel.repoDownloadFileName.value ?: "repository_${System.currentTimeMillis()}.zip"
            createDocumentLauncher.launch(fileName)
        }
    }

    MaterialTheme(typography = scaledTypography) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "GitHub Explorer - ${viewModel.currentRepo.value?.fullName ?: "Guest"}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions = {
                        var showMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Preferences") },
                                onClick = {
                                    showMenu = false
                                    viewModel.showSettingsDialog.value = true
                                }
                            )
                        }
                    }
                )
            },
            bottomBar = {
                BottomAppBar(modifier = Modifier.height(36.dp)) {
                    Text(text = statusMessage.toString(), modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        ) { paddingValues ->
            val repos = viewModel.repos
            val isLoading = viewModel.isLoadingRepos.value || viewModel.isLoadingTree.value
            
            // 当没有仓库且未加载时，提示用户
            if (repos.isEmpty() && !isLoading) {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues), 
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No repositories found.")
                        Text("Is your token valid?", modifier = Modifier.padding(top = 8.dp))
                        Button(
                            onClick = { viewModel.loadMyRepos() },
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text("Try Again")
                        }
                    }
                }
            } else {
                val configuration = LocalConfiguration.current
                val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                
                if (isPortrait) {
                    // 竖屏：上下布局
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        // 上方区域：目录树/Actions 面板，占据 40% 高度
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.4f)
                        ) {
                            val pagerState = rememberPagerState(pageCount = { 3 })
                            val tabs = listOf("Repositories", "Directory Tree", "Actions")

                            TabRow(selectedTabIndex = pagerState.currentPage) {
                                tabs.forEachIndexed { index, title ->
                                    Tab(
                                        selected = pagerState.currentPage == index,
                                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                        text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    )
                                }
                            }
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                when (it) {
                                    0 -> RepositoriesTab(viewModel = viewModel)
                                    1 -> DirectoryTreeTab(viewModel = viewModel)
                                    2 -> ActionsTab(viewModel = viewModel)
                                }
                            }
                        }

                        // 水平分割线
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outline)
                        )

                        // 下方区域：文件预览区，占据 60% 高度
                        ContentDisplay(
                            viewModel = viewModel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.6f)
                        )
                    }
                } else {
                    // 横屏：保持原有左右布局
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        // 左侧面板（原代码）
                        Column(modifier = Modifier.width(280.dp)) {
                            val pagerState = rememberPagerState(pageCount = { 3 })
                            val tabs = listOf("Repositories", "Directory Tree", "Actions")

                            TabRow(selectedTabIndex = pagerState.currentPage) {
                                tabs.forEachIndexed { index, title ->
                                    Tab(
                                        selected = pagerState.currentPage == index,
                                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                        text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    )
                                }
                            }
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                when (it) {
                                    0 -> RepositoriesTab(viewModel = viewModel)
                                    1 -> DirectoryTreeTab(viewModel = viewModel)
                                    2 -> ActionsTab(viewModel = viewModel)
                                }
                            }
                        }

                        // 垂直分割线
                        Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outline))

                        // 右侧内容区域
                        ContentDisplay(viewModel = viewModel, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (viewModel.showSettingsDialog.value) {
        SettingsDialog(
            currentScale = fontScale,
            onScaleChange = { viewModel.updateFontScale(it) },
            onDismiss = { viewModel.showSettingsDialog.value = false },
            onRestartNeeded = {
                currentContext.recreate()
            }
        )
    }

    if (viewModel.showArtifactSelectionDialog.value) {
        ArtifactSelectionDialog(
            artifacts = viewModel.availableArtifacts,
            onSelect = { artifact ->
                viewModel.selectArtifactForDownload(artifact)
            },
            onDismiss = { viewModel.showArtifactSelectionDialog.value = false }
        )
    }
}

// ----- Sub-Composables -----

@Composable
fun RepositoriesTab(viewModel: MainViewModel) {
    val searchQuery by viewModel.searchQuery
    val repos = viewModel.repos
    val isLoading by viewModel.isLoadingRepos
    val showBranch by viewModel.showBranchInRepoList
    val isDownloading by viewModel.isDownloadingRepo

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.searchQuery.value = it },
            label = { Text("Search repositories") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Button(onClick = { viewModel.loadMyRepos() }, enabled = !isLoading) { Text("My Repos") }
            Button(onClick = { viewModel.searchRepos() }, enabled = !isLoading) { Text("Search") }
            Button(onClick = { viewModel.loadMyRepos() }, enabled = !isLoading) { Text("Refresh") }
        }
        // Branch visibility toggle row
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = showBranch,
                    onCheckedChange = { viewModel.toggleBranchVisibility() }
                )
                Text(
                    text = "Show Branch",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(repos) { repo ->
                val isSelected = viewModel.currentRepo.value?.fullName == repo.fullName
                ListItem(
                    modifier = Modifier
                        .clickable { viewModel.selectRepo(repo) }
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        ),
                    headlineContent = { Text(repo.fullName, fontWeight = FontWeight.Bold) },
                    supportingContent = {
                        repo.description?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (showBranch) {
                                Text(
                                    text = repo.defaultBranch,
                                    modifier = Modifier.width(60.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { viewModel.startRepoDownload(repo) },
                                enabled = !isDownloading && !viewModel.isLoadingRepos.value
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = "Download Repository"
                                )
                            }
                        }
                    }
                )
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
            }
        }
    }
}

@Composable
fun DirectoryTreeTab(viewModel: MainViewModel) {
    val treeNodes = viewModel.treeEntries
    val expandedNodesMap = viewModel.expandedNodes
    val isLoading by viewModel.isLoadingTree

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (treeNodes.isEmpty() && !isLoading) {
            Text("Select a repository or directory tree is empty.", modifier = Modifier.padding(16.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(treeNodes, key = { it.path }) { node ->
                TreeItem(node = node, viewModel = viewModel, expandedNodesMap = expandedNodesMap, depth = 0)
            }
        }
    }
}

@Composable
fun TreeItem(node: TreeEntryNode, viewModel: MainViewModel, expandedNodesMap: Map<String, Boolean>, depth: Int) {
    val isExpanded = expandedNodesMap[node.path] ?: false
    val horizontalPadding = (depth * 16).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (node.type == "tree") {
                    viewModel.toggleNodeExpansion(node.path)
                } else {
                    viewModel.displayFileContent(node.path)
                }
            }
            .padding(start = horizontalPadding, top = 4.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.type == "tree") {
            Icon(
                imageVector = if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp)
            )
        } else {
            Spacer(modifier = Modifier.width(20.dp))
        }
        Icon(
            imageVector = if (node.type == "tree") Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = node.type,
            modifier = Modifier.size(20.dp).padding(horizontal = 4.dp)
        )
        Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    if (isExpanded && node.type == "tree") {
        node.children.forEach { child ->
            TreeItem(node = child, viewModel = viewModel, expandedNodesMap = expandedNodesMap, depth = depth + 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsTab(viewModel: MainViewModel) {
    val currentRepo by viewModel.currentRepo
    val workflows = viewModel.workflows
    val selectedWorkflow by viewModel.selectedWorkflow
    val workflowRuns = viewModel.workflowRuns
    val workflowRunStatusFilter by viewModel.workflowRunStatusFilter
    val isLoadingWorkflows by viewModel.isLoadingWorkflows
    val isLoadingRuns by viewModel.isLoadingRuns

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Workflow:", modifier = Modifier.weight(0.2f))
            WorkflowSelector(
                workflows = workflows,
                selectedWorkflow = selectedWorkflow,
                onWorkflowSelected = { viewModel.selectWorkflow(it) },
                modifier = Modifier.weight(0.8f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Status:", modifier = Modifier.weight(0.2f))
            val statusOptions = listOf("所有", "进行中", "成功", "失败")
            var expanded by remember { mutableStateOf(false) }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.weight(0.4f)
            ) {
                OutlinedTextField(
                    value = workflowRunStatusFilter,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Filter Status") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    statusOptions.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(status) },
                            onClick = {
                                viewModel.workflowRunStatusFilter.value = status
                                viewModel.loadWorkflowRuns()
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(onClick = { viewModel.loadWorkflows() }, enabled = !isLoadingWorkflows) { Text("Refresh") }
            Spacer(modifier = Modifier.width(4.dp))
            Button(
                onClick = { viewModel.triggerWorkflow() },
                enabled = !isLoadingWorkflows && selectedWorkflow != null && currentRepo != null
            ) {
                Text("Trigger")
            }
        }

        if (isLoadingWorkflows || isLoadingRuns) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(workflowRuns, key = { it.id }) { run ->
                RunItem(run = run, viewModel = viewModel)
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowSelector(
    workflows: List<Workflow>,
    selectedWorkflow: Workflow?,
    onWorkflowSelected: (Workflow) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedWorkflow?.name ?: "Select Workflow",
            onValueChange = {},
            readOnly = true,
            label = { Text("Workflow") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            workflows.forEach { workflow ->
                DropdownMenuItem(
                    text = { Text(workflow.name) },
                    onClick = {
                        onWorkflowSelected(workflow)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun RunItem(run: WorkflowRun, viewModel: MainViewModel) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val time = try { formatter.format(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(run.createdAt)) } catch (e: Exception) { run.createdAt }

    val statusDisplay = run.conclusion?.replaceFirstChar { it.uppercase() } ?: run.status.replaceFirstChar { it.uppercase() }
    val canDownload = run.status == "completed" && run.conclusion == "success"

    ListItem(
        modifier = Modifier.clickable { viewModel.showRunLog(run.id) },
        headlineContent = { Text("Run ${run.id}", fontWeight = FontWeight.Bold) },
        supportingContent = { Text("Branch: ${run.headBranch}") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusDisplay, modifier = Modifier.width(60.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(time, modifier = Modifier.width(60.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (canDownload) {
                    IconButton(onClick = { viewModel.startArtifactDownload(run) }) {
                        Icon(Icons.Filled.Download, contentDescription = "Download Artifact")
                    }
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }
            }
        }
    )
}

@Composable
fun ContentDisplay(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val fileContent by viewModel.fileContent
    val isLoadingContent by viewModel.isLoadingContent
    val isLoadingRunLog by viewModel.isLoadingRunLog
    val isBinaryFile by viewModel.isBinaryFile

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        if (isLoadingContent || isLoadingRunLog) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (fileContent == null) {
            Text("Select a file or workflow run log to display content.", modifier = Modifier.padding(16.dp))
        } else {
            val scrollState = rememberScrollState()
            Text(
                text = fileContent!!,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = if (isBinaryFile) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun SettingsDialog(
    currentScale: Float,
    onScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    onRestartNeeded: () -> Unit
) {
    var tempScale by remember { mutableStateOf(currentScale) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Preferences") },
        text = {
            Column {
                Text("UI Font Scale: ${String.format("%.1f", tempScale)}x")
                Slider(
                    value = tempScale,
                    onValueChange = { tempScale = it },
                    valueRange = 0.8f..2.0f,
                    steps = 11,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (tempScale != currentScale) {
                    onScaleChange(tempScale)
                    onRestartNeeded()
                }
                onDismiss()
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ArtifactSelectionDialog(
    artifacts: List<Artifact>,
    onSelect: (Artifact) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedArtifact by remember { mutableStateOf<Artifact?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Artifact to Download") },
        text = {
            Column {
                artifacts.forEach { artifact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedArtifact = artifact }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedArtifact == artifact,
                            onClick = { selectedArtifact = artifact }
                        )
                        Text("${artifact.name} (${String.format("%.1f", artifact.sizeInBytes / 1024f / 1024f)} MB")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    selectedArtifact?.let {
                        onSelect(it)
                    } ?: onDismiss()
                },
                enabled = selectedArtifact != null
            ) {
                Text("Download")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}