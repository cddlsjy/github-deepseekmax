package com.gemini.githubexplorer.ui.main

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gemini.githubexplorer.AppConfig
import com.gemini.githubexplorer.ConfigManager
import com.gemini.githubexplorer.data.github.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

data class CurrentRepoInfo(
    val owner: String,
    val repoName: String,
    val branch: String,
    val fullName: String
)

class MainViewModel(
    private val context: Context,
    private val configManager: ConfigManager,
    private val repository: GitHubRepository,
    private val appConfig: AppConfig
) : ViewModel() {

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _showSnackbar = MutableStateFlow<String?>(null)
    val showSnackbar: StateFlow<String?> = _showSnackbar.asStateFlow()

    private val _showDialog = MutableStateFlow<String?>(null)
    val showDialog: StateFlow<String?> = _showDialog.asStateFlow()

    val showSettingsDialog = mutableStateOf(false)

    fun showSnackbarMessage(message: String) { _showSnackbar.value = message }
    fun dismissSnackbar() { _showSnackbar.value = null }
    fun showAlertDialog(message: String) { _showDialog.value = message }
    fun dismissAlertDialog() { _showDialog.value = null }

    private fun setStatus(message: String) { _statusMessage.value = message }

    // Repositories Tab
    val searchQuery = mutableStateOf(appConfig.lastSearchQuery)
    val repos = mutableStateListOf<GitHubRepo>()
    val isLoadingRepos = mutableStateOf(false)
    val currentRepo = mutableStateOf<CurrentRepoInfo?>(null)

    // Directory Tree Tab
    val treeEntries = mutableStateListOf<TreeEntryNode>()
    val expandedNodes = mutableStateMapOf<String, Boolean>()
    val isLoadingTree = mutableStateOf(false)

    // File Content Display
    val fileContent = mutableStateOf<String?>(null)
    val isBinaryFile = mutableStateOf(false)
    val isLoadingContent = mutableStateOf(false)

    // Actions Tab
    val workflows = mutableStateListOf<Workflow>()
    val selectedWorkflow = mutableStateOf<Workflow?>(null)
    val workflowRuns = mutableStateListOf<WorkflowRun>()
    val selectedRun = mutableStateOf<WorkflowRun?>(null)
    val workflowRunStatusFilter = mutableStateOf("所有")
    val isLoadingWorkflows = mutableStateOf(false)
    val isLoadingRuns = mutableStateOf(false)
    val isLoadingRunLog = mutableStateOf(false)
    val pollingJob = mutableStateOf<Job?>(null)

    val fontScale = mutableStateOf(appConfig.fontScale)

    init {
        loadMyRepos()
        appConfig.lastRepoFullName.takeIf { it.isNotEmpty() }?.let { lastRepoFullName ->
            viewModelScope.launch {
                val parts = lastRepoFullName.split("/")
                if (parts.size == 2) {
                    val owner = parts[0]
                    val repoName = parts[1]
                    try {
                        val repoData = repository.getRepo(owner, repoName)
                        val branch = repository.getRepoDefaultBranch(owner, repoName)
                        currentRepo.value = CurrentRepoInfo(owner, repoName, branch, repoData.fullName)
                        loadRepoTree(owner, repoName, branch)
                        loadWorkflows()
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Failed to load last repo: $lastRepoFullName", e)
                        showSnackbarMessage("Failed to load last repo: ${e.message}")
                    }
                }
            }
        }
    }

    fun loadMyRepos() {
        if (isLoadingRepos.value) return
        isLoadingRepos.value = true
        setStatus("Loading my repositories...")
        viewModelScope.launch {
            try {
                val fetchedRepos = repository.listUserRepos()
                repos.clear()
                // 先添加到列表，再排序，避免空指针异常
                repos.addAll(fetchedRepos)
                // 排序时处理 null 值
                repos.sortByDescending { it.updatedAt ?: "" }
                if (repos.isEmpty()) {
                    showSnackbarMessage("No repositories found.")
                    // 显示调试信息
                    showSnackbarMessage("Fetched repos count: ${fetchedRepos.size}")
                }
            } catch (e: Exception) {
                showSnackbarMessage("Failed to load repositories: ${e.message}")
                // 弹窗显示错误
                _showDialog.value = "Load failed: ${e.message}"
                // 显示详细错误信息
                showSnackbarMessage("Error details: ${e.stackTraceToString()}")
            } finally {
                isLoadingRepos.value = false
                setStatus("Ready")
            }
        }
    }

    fun searchRepos() {
        val query = searchQuery.value.trim()
        if (query.isBlank()) {
            showSnackbarMessage("Search query cannot be empty.")
            return
        }
        if (isLoadingRepos.value) return
        isLoadingRepos.value = true
        setStatus("Searching repositories...")
        viewModelScope.launch {
            try {
                val fetchedRepos = repository.searchRepos(query)
                repos.clear()
                repos.addAll(fetchedRepos)
                if (repos.isEmpty()) showSnackbarMessage("No matching repositories found.")
                configManager.saveLastSearchQuery(query)
            } catch (e: Exception) {
                showSnackbarMessage("Failed to search repositories: ${e.message}")
            } finally {
                isLoadingRepos.value = false
                setStatus("Ready")
            }
        }
    }

    fun selectRepo(repo: GitHubRepo) {
        val owner = repo.owner.login
        val repoName = repo.name
        val fullName = repo.fullName
        setStatus("Fetching default branch...")
        viewModelScope.launch {
            try {
                val branch = repository.getRepoDefaultBranch(owner, repoName)
                currentRepo.value = CurrentRepoInfo(owner, repoName, branch, fullName)
                loadRepoTree(owner, repoName, branch)
                loadWorkflows()
                configManager.saveLastRepoFullName(fullName)
                // Hide branch in repo list for space efficiency
                showBranchInRepoList.value = false
            } catch (e: Exception) {
                showSnackbarMessage("Failed to get default branch: ${e.message}")
                setStatus("Ready")
            }
        }
    }

    fun startRepoDownload(repo: GitHubRepo) {
        setStatus("Preparing repository download...")
        isDownloadingRepo.value = true
        viewModelScope.launch {
            try {
                val branch = repository.getRepoDefaultBranch(repo.owner.login, repo.name)
                val zipballUrl = repository.getRepoZipballUrl(repo.owner.login, repo.name, branch)
                repoDownloadUrl.value = zipballUrl
                repoDownloadFileName.value = "${repo.name}-${branch}.zip"
                setStatus("Ready")
            } catch (e: Exception) {
                showSnackbarMessage("Failed to prepare repository download: ${e.message}")
                setStatus("Ready")
                isDownloadingRepo.value = false
            }
        }
    }

    suspend fun performRepoDownload(uri: Uri, downloadUrl: String) = withContext(Dispatchers.IO) {
        setStatus("Downloading repository...")
        try {
            val tempFile = java.io.File(context.cacheDir, "temp_repo_${System.currentTimeMillis()}.zip")
            repository.downloadRepoZipball(downloadUrl, tempFile)

            context.contentResolver.openOutputStream(uri)?.use { os ->
                tempFile.inputStream().use { input ->
                    input.copyTo(os)
                }
            } ?: throw Exception("Could not open output stream for URI: $uri")

            tempFile.delete()
            showSnackbarMessage("Repository downloaded successfully!")
            setStatus("Ready")
        } catch (e: Exception) {
            showSnackbarMessage("Error downloading repository: ${e.message}")
            setStatus("Ready")
        } finally {
            isDownloadingRepo.value = false
        }
    }

    fun toggleBranchVisibility() {
        showBranchInRepoList.value = !showBranchInRepoList.value
    }

    private fun loadRepoTree(owner: String, repoName: String, branch: String) {
        if (isLoadingTree.value) return
        isLoadingTree.value = true
        setStatus("Loading directory tree...")
        treeEntries.clear()
        expandedNodes.clear()
        viewModelScope.launch {
            try {
                val entries = repository.getRepoTree(owner, repoName, branch)
                val rootNodes = buildTree(entries)
                treeEntries.addAll(rootNodes)
            } catch (e: Exception) {
                showSnackbarMessage("Failed to load directory tree: ${e.message}")
            } finally {
                isLoadingTree.value = false
                setStatus("Ready")
            }
        }
    }

    private fun buildTree(flatEntries: List<TreeEntry>): List<TreeEntryNode> {
        val rootNodes = mutableListOf<TreeEntryNode>()
        val allNodesMap = mutableMapOf<String, TreeEntryNode>()

        for (entry in flatEntries) {
            val node = TreeEntryNode(
                path = entry.path,
                name = entry.path.substringAfterLast('/'),
                type = entry.type,
                children = mutableListOf()
            )
            allNodesMap[entry.path] = node
        }

        for (node in allNodesMap.values) {
            val parentPath = node.path.substringBeforeLast('/', "")
            if (parentPath.isEmpty()) {
                rootNodes.add(node)
            } else {
                allNodesMap[parentPath]?.children?.add(node)
            }
        }

        fun sortChildren(nodes: List<TreeEntryNode>) {
            nodes.forEach { node ->
                node.children.sortWith(compareBy({ it.type != "tree" }, { it.name.lowercase(Locale.ROOT) }))
                sortChildren(node.children)
            }
        }
        sortChildren(rootNodes)
        rootNodes.sortWith(compareBy({ it.type != "tree" }, { it.name.lowercase(Locale.ROOT) }))

        return rootNodes
    }

    fun toggleNodeExpansion(nodePath: String) {
        expandedNodes[nodePath] = !(expandedNodes[nodePath] ?: false)
    }

    fun displayFileContent(path: String) {
        val repoInfo = currentRepo.value ?: return
        clearContent()
        isLoadingContent.value = true
        setStatus("Loading file content...")
        viewModelScope.launch {
            try {
                val content = repository.getFileContent(repoInfo.owner, repoInfo.repoName, path, repoInfo.branch)
                when (content) {
                    is String -> fileContent.value = content
                    is ByteArray -> {
                        fileContent.value = "[Binary file, cannot be previewed directly]"
                        isBinaryFile.value = true
                    }
                    else -> fileContent.value = "Cannot retrieve content for this item."
                }
            } catch (e: Exception) {
                fileContent.value = "Error loading content: ${e.message}"
            } finally {
                isLoadingContent.value = false
                setStatus("Ready")
            }
        }
    }

    private fun clearContent() {
        fileContent.value = null
        isBinaryFile.value = false
        isLoadingContent.value = false
    }

    // --- Actions Tab Logic ---

    fun loadWorkflows() {
        val repoInfo = currentRepo.value ?: run {
            showSnackbarMessage("Please select a repository first.")
            return
        }
        if (isLoadingWorkflows.value) return
        isLoadingWorkflows.value = true
        setStatus("Loading workflows...")
        viewModelScope.launch {
            try {
                val fetchedWorkflows = repository.listWorkflows(repoInfo.owner, repoInfo.repoName)
                workflows.clear()
                workflows.addAll(fetchedWorkflows)
                if (workflows.isNotEmpty()) {
                    selectedWorkflow.value = workflows.first()
                    loadWorkflowRuns()
                } else {
                    selectedWorkflow.value = null
                    workflowRuns.clear()
                }
                setStatus("Loaded ${workflows.size} workflows.")
            } catch (e: Exception) {
                showSnackbarMessage("Failed to load workflows: ${e.message}")
            } finally {
                isLoadingWorkflows.value = false
                setStatus("Ready")
            }
        }
    }

    fun selectWorkflow(workflow: Workflow) {
        selectedWorkflow.value = workflow
        loadWorkflowRuns()
    }

    fun loadWorkflowRuns() {
        val repoInfo = currentRepo.value ?: return
        val wf = selectedWorkflow.value ?: run {
            workflowRuns.clear()
            return
        }
        if (isLoadingRuns.value) return
        isLoadingRuns.value = true
        setStatus("Loading workflow runs...")
        pollingJob.value?.cancel()

        viewModelScope.launch {
            try {
                val apiStatus = when (workflowRunStatusFilter.value) {
                    "进行中" -> "in_progress"
                    "成功" -> "success"
                    "失败" -> "failure"
                    else -> null
                }
                val fetchedRuns = repository.listWorkflowRuns(
                    repoInfo.owner, repoInfo.repoName, wf.id, apiStatus
                )
                workflowRuns.clear()
                workflowRuns.addAll(fetchedRuns)

                if (fetchedRuns.any { it.status == "in_progress" || it.status == "queued" }) {
                    startPollingRuns()
                } else {
                    setStatus("Loaded ${workflowRuns.size} workflow runs.")
                }
            } catch (e: Exception) {
                showSnackbarMessage("Failed to load workflow runs: ${e.message}")
            } finally {
                isLoadingRuns.value = false
            }
        }
    }

    private fun startPollingRuns() {
        pollingJob.value?.cancel()
        pollingJob.value = viewModelScope.launch {
            val repoInfo = currentRepo.value ?: return@launch
            val wf = selectedWorkflow.value ?: return@launch
            setStatus("Polling workflow runs...")
            while (true) {
                delay(5000)
                try {
                    val apiStatus = when (workflowRunStatusFilter.value) {
                        "进行中" -> "in_progress"
                        "成功" -> "success"
                        "失败" -> "failure"
                        else -> null
                    }
                    val fetchedRuns = repository.listWorkflowRuns(
                        repoInfo.owner, repoInfo.repoName, wf.id, apiStatus
                    )
                    workflowRuns.clear()
                    workflowRuns.addAll(fetchedRuns)

                    if (!fetchedRuns.any { it.status == "in_progress" || it.status == "queued" }) {
                        setStatus("Workflow runs updated. Polling stopped.")
                        break
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error during polling: ${e.message}")
                    setStatus("Error polling runs. Polling stopped.")
                    break
                }
            }
        }
    }

    fun triggerWorkflow() {
        val repoInfo = currentRepo.value ?: run {
            showSnackbarMessage("Please select a repository first.")
            return
        }
        val wf = selectedWorkflow.value ?: run {
            showSnackbarMessage("Please select a workflow to trigger.")
            return
        }
        setStatus("Dispatching workflow...")
        clearContent()
        fileContent.value = "Dispatching workflow '${wf.name}'...\n"
        viewModelScope.launch {
            try {
                val response = repository.dispatchWorkflow(repoInfo.owner, repoInfo.repoName, wf.id, repoInfo.branch)
                if (!response.isSuccessful) {
                    val errorMsg = response.errorBody()?.string()
                    throw Exception("Dispatch failed: ${response.code()} - $errorMsg")
                }
                // 成功
                fileContent.value += "Workflow dispatched successfully. Waiting for run to start...\n"
                setStatus("Workflow dispatched.")
                delay(2000)
                loadWorkflowRuns()
            } catch (e: Exception) {
                fileContent.value += "Failed to dispatch workflow: ${e.message}\n"
                showSnackbarMessage("Failed to dispatch workflow: ${e.message}")
                setStatus("Dispatch failed.")
            }
        }
    }

    fun showRunLog(runId: Long) {
        val repoInfo = currentRepo.value ?: return
        clearContent()
        isLoadingRunLog.value = true
        fileContent.value = "Loading log for Run #$runId...\n"
        setStatus("Loading run log...")
        viewModelScope.launch {
            try {
                val log = repository.getRunLogsCombined(repoInfo.owner, repoInfo.repoName, runId)
                fileContent.value = log
                setStatus("Log loaded for Run #$runId.")
            } catch (e: Exception) {
                fileContent.value = "Error loading log: ${e.message}"
                showSnackbarMessage("Failed to load run log: ${e.message}")
            } finally {
                isLoadingRunLog.value = false
            }
        }
    }

    // Artifact download logic
    val artifactDownloadUrl = mutableStateOf<String?>(null)
    val artifactFileName = mutableStateOf<String?>(null)
    val showArtifactSelectionDialog = mutableStateOf(false)
    val availableArtifacts = mutableStateListOf<Artifact>()

    // Repository download logic
    val repoDownloadUrl = mutableStateOf<String?>(null)
    val repoDownloadFileName = mutableStateOf<String?>(null)
    val isDownloadingRepo = mutableStateOf(false)

    // Branch visibility toggle (for compact view when repo is selected)
    val showBranchInRepoList = mutableStateOf(true)

    fun startArtifactDownload(run: WorkflowRun) {
        val repoInfo = currentRepo.value ?: run {
            showSnackbarMessage("Please select a repository first.")
            return
        }
        setStatus("Fetching artifacts...")
        viewModelScope.launch {
            try {
                val artifacts = repository.listRunArtifacts(repoInfo.owner, repoInfo.repoName, run.id)
                if (artifacts.isEmpty()) {
                    showSnackbarMessage("No artifacts found for this run.")
                    setStatus("Ready")
                    return@launch
                }

                availableArtifacts.clear()
                val apkArtifacts = artifacts.filter { it.name.contains("apk", ignoreCase = true) }
                if (apkArtifacts.isNotEmpty()) {
                    availableArtifacts.addAll(apkArtifacts)
                } else {
                    availableArtifacts.addAll(artifacts)
                }

                if (availableArtifacts.size == 1) {
                    val artifact = availableArtifacts.first()
                    artifactDownloadUrl.value = artifact.archiveDownloadUrl
                    artifactFileName.value = "${artifact.name}.zip"
                } else {
                    showArtifactSelectionDialog.value = true
                }
            } catch (e: Exception) {
                showSnackbarMessage("Failed to list artifacts: ${e.message}")
                setStatus("Ready")
            }
        }
    }

    fun selectArtifactForDownload(artifact: Artifact) {
        artifactDownloadUrl.value = artifact.archiveDownloadUrl
        artifactFileName.value = "${artifact.name}.zip"
        showArtifactSelectionDialog.value = false
    }

    suspend fun performArtifactDownload(uri: Uri, artifactUrl: String) = withContext(Dispatchers.IO) {
        setStatus("Downloading artifact...")
        try {
            val outputStream = context.contentResolver.openOutputStream(uri)
            outputStream?.use { os ->
                repository.okHttpClient.newCall(okhttp3.Request.Builder().url(artifactUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Failed to download artifact: ${response.code} ${response.message}")
                    }
                    response.body?.byteStream()?.use { input ->
                        input.copyTo(os)
                    } ?: throw IOException("Empty response body")
                }
            } ?: throw IOException("Could not open output stream for URI: $uri")
            showSnackbarMessage("Artifact downloaded successfully!")
            setStatus("Ready")
        } catch (e: Exception) {
            showSnackbarMessage("Error downloading artifact: ${e.message}")
            setStatus("Ready")
        }
    }

    fun updateFontScale(newScale: Float) {
        fontScale.value = newScale
        viewModelScope.launch {
            configManager.saveFontScale(newScale)
        }
    }

    class Factory(
        private val context: Context,
        private val repository: GitHubRepository,
        private val config: AppConfig
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(context, ConfigManager(context), repository, config) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

// Helper data class for directory tree nodes
data class TreeEntryNode(
    val path: String,
    val name: String,
    val type: String,
    val children: MutableList<TreeEntryNode>
)
