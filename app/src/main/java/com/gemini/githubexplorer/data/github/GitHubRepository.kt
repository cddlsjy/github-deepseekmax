package com.gemini.githubexplorer.data.github

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class GitHubRepository(private val api: GitHubApi, val okHttpClient: OkHttpClient) {

    // 辅助函数：解析重定向，获取真实下载URL
    private suspend fun resolveRedirect(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code in 300..399) {
                response.header("Location") ?: throw IOException("Redirect without Location header")
            } else {
                url
            }
        }
    }

    suspend fun getUser(): GitHubUser = api.getUser()

    suspend fun listUserRepos(): List<GitHubRepo> = withContext(Dispatchers.IO) {
        val allRepos = mutableListOf<GitHubRepo>()
        var page = 1
        while (true) {
            try {
                val repos = api.listUserRepos(page = page)
                println("Fetched ${repos.size} repos from page $page")
                if (repos.isEmpty()) break
                allRepos.addAll(repos)
                if (repos.size < 100) break
                page++
            } catch (e: Exception) {
                println("Error fetching repos: ${e.message}")
                throw e
            }
        }
        println("Total repos fetched: ${allRepos.size}")
        allRepos
    }

    suspend fun searchRepos(query: String): List<GitHubRepo> = withContext(Dispatchers.IO) {
        val allRepos = mutableListOf<GitHubRepo>()
        var page = 1
        while (true) {
            val response = api.searchRepos(query = query, page = page)
            val repos = response.items
            if (repos.isEmpty()) break
            allRepos.addAll(repos)
            if (repos.size < 100) break
            page++
        }
        allRepos
    }

    suspend fun getRepoDefaultBranch(owner: String, repo: String): String {
        return api.getRepo(owner, repo).defaultBranch
    }

    suspend fun getRepo(owner: String, repo: String): GitHubRepo {
        return api.getRepo(owner, repo)
    }

    suspend fun getRepoTree(owner: String, repo: String, branch: String, recursive: Boolean = true): List<TreeEntry> = withContext(Dispatchers.IO) {
        val ref = api.getBranchRef(owner, repo, branch)
        val commitSha = ref.`object`.sha
        val recursiveParam = if (recursive) 1 else null
        api.getRepoTree(owner, repo, commitSha, recursiveParam).tree
    }

    suspend fun getFileContent(owner: String, repo: String, path: String, branch: String = "main"): Any? = withContext(Dispatchers.IO) {
        try {
            val encodedPath = GitHubHttpClient.encodePath(path)
            val response = api.getFileContent(owner, repo, encodedPath, branch)
            if (response.type == "dir") {
                return@withContext null
            }

            if (response.encoding == "base64") {
                val decoded = Base64.decode(response.content, Base64.DEFAULT)
                try {
                    return@withContext decoded.toString(Charsets.UTF_8)
                } catch (e: Exception) {
                    return@withContext decoded
                }
            }
            return@withContext response.content
        } catch (e: Exception) {
            throw e
        }
    }

    // --- Actions API ---

    suspend fun listWorkflows(owner: String, repo: String): List<Workflow> {
        return api.listWorkflows(owner, repo).workflows
    }

    suspend fun dispatchWorkflow(owner: String, repo: String, workflowId: Long, ref: String): retrofit2.Response<okhttp3.ResponseBody> {
        return api.dispatchWorkflow(owner, repo, workflowId, GitHubApi.WorkflowDispatchPayload(ref))
    }

    suspend fun listWorkflowRuns(owner: String, repo: String, workflowId: Long, status: String? = null, perPage: Int = 30, page: Int = 1): List<WorkflowRun> {
        return api.listWorkflowRuns(owner, repo, workflowId, status, perPage, page).workflowRuns
    }

    suspend fun getRunJobs(owner: String, repo: String, runId: Long): List<Job> {
        return api.getRunJobs(owner, repo, runId).jobs
    }

    suspend fun getJobLog(owner: String, repo: String, jobId: Long): String = withContext(Dispatchers.IO) {
        val response = api.getJobLog(owner, repo, jobId)

        if (response.isSuccessful) {
            // 处理重定向 (GitHub 日志返回 302)
            val location = response.headers()["Location"]
            response.body()?.close()  // 关闭 ResponseBody

            if (location != null) {
                // 手动请求重定向 URL
                val logRequest = Request.Builder().url(location).build()
                val logResponse = okHttpClient.newCall(logRequest).execute()
                if (logResponse.isSuccessful) {
                    return@withContext logResponse.body?.string() ?: ""
                } else {
                    throw Exception("Failed to download log from redirect: ${logResponse.code}")
                }
            }
            // 无重定向，返回错误信息
            throw Exception("Job log response successful but no content and no redirect location")
        } else {
            throw Exception("Failed to get job log: ${response.code()}")
        }
    }

    suspend fun getRunLogsCombined(owner: String, repo: String, runId: Long): String = withContext(Dispatchers.IO) {
        val jobs = getRunJobs(owner, repo, runId)
        val logParts = jobs.map { job ->
            async {
                try {
                    val log = getJobLog(owner, repo, job.id)
                    "--- Job: ${job.name} (${job.id}) ---\n$log\n"
                } catch (e: Exception) {
                    "--- Job: ${job.name} (${job.id}) - Error loading log ---\n${e.message}\n"
                }
            }
        }.awaitAll()
        logParts.joinToString("\n")
    }

    suspend fun listRunArtifacts(owner: String, repo: String, runId: Long): List<Artifact> {
        return api.listRunArtifacts(owner, repo, runId).artifacts
    }

    suspend fun downloadArtifact(artifactUrl: String, destinationFile: File) = withContext(Dispatchers.IO) {
        // 先解析重定向获取真实下载URL
        val realUrl = resolveRedirect(artifactUrl)

        okHttpClient.newCall(Request.Builder().url(realUrl).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to download artifact: ${response.code}")
            }
            response.body?.byteStream()?.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Empty response body for artifact download")
        }
    }

    // Repository zipball download - returns the download URL
    suspend fun getRepoZipballUrl(owner: String, repo: String, ref: String): String = withContext(Dispatchers.IO) {
        val retrofitResponse = api.getRepoZipball(owner, repo, ref)

        // GitHub returns 302 redirect, handle both redirect and success cases
        val code = retrofitResponse.code()
        if (code in 300..399) {
            // Handle redirect
            val redirectUrl = retrofitResponse.headers()["Location"]
            retrofitResponse.body()?.close()
            redirectUrl ?: throw Exception("Failed to get repository zipball URL: No Location header found in redirect response")
            return@withContext redirectUrl
        } else if (retrofitResponse.isSuccessful) {
            // Direct success (uncommon but possible)
            retrofitResponse.body()?.close()
            return@withContext "https://api.github.com/repos/$owner/$repo/zipball/$ref"
        } else {
            val errorBody = retrofitResponse.errorBody()?.string()
            throw Exception("Failed to get repository zipball: $code - ${errorBody ?: retrofitResponse.message()}")
        }
    }

    // Download repository zipball to a file (url is already the resolved redirect URL)
    suspend fun downloadRepoZipball(zipballUrl: String, destinationFile: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(zipballUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to download repository: ${response.code}")
            }
            response.body?.byteStream()?.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Empty response body for repository download")
        }
    }
}
