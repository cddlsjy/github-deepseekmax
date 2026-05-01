package com.gemini.githubexplorer.data.github

import com.gemini.githubexplorer.Constants
import com.google.gson.annotations.SerializedName
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response as OkHttpResponse
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// --- Data Models ---

data class GitHubUser(
    val login: String,
    val id: Long,
    @SerializedName("avatar_url") val avatarUrl: String,
    val name: String?
)

data class GitHubRepo(
    val id: Long,
    @SerializedName("full_name") val fullName: String,
    val name: String,
    val owner: GitHubUser,
    val description: String?,
    @SerializedName("default_branch") val defaultBranch: String,
    @SerializedName("updated_at") val updatedAt: String? = null
)

data class TreeEntry(
    val path: String,
    val mode: String,
    val type: String,
    val sha: String,
    val size: Long? = null,
    val url: String
)

data class GitTreeResponse(
    val sha: String,
    val url: String,
    val tree: List<TreeEntry>,
    val truncated: Boolean
)

data class FileContentResponse(
    val name: String,
    val path: String,
    val sha: String,
    val size: Long,
    val url: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("git_url") val gitUrl: String,
    val downloadUrl: String?,
    val type: String,
    val content: String,
    val encoding: String
)

data class Workflow(
    val id: Long,
    val name: String,
    val path: String,
    val state: String,
    @SerializedName("html_url") val htmlUrl: String
)

data class WorkflowsResponse(
    @SerializedName("total_count") val totalCount: Int,
    val workflows: List<Workflow>
)

data class WorkflowRun(
    val id: Long,
    val name: String?,
    @SerializedName("head_branch") val headBranch: String,
    val status: String,
    val conclusion: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("workflow_id") val workflowId: Long
)

data class WorkflowRunsResponse(
    @SerializedName("total_count") val totalCount: Int,
    @SerializedName("workflow_runs") val workflowRuns: List<WorkflowRun>
)

data class Job(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    @SerializedName("started_at") val startedAt: String,
    @SerializedName("completed_at") val completedAt: String?,
    @SerializedName("html_url") val htmlUrl: String
)

data class JobsResponse(
    @SerializedName("total_count") val totalCount: Int,
    val jobs: List<Job>
)

data class Artifact(
    val id: Long,
    val name: String,
    @SerializedName("size_in_bytes") val sizeInBytes: Long,
    @SerializedName("archive_download_url") val archiveDownloadUrl: String,
    @SerializedName("expired") val expired: Boolean
)

data class ArtifactsResponse(
    @SerializedName("total_count") val totalCount: Int,
    val artifacts: List<Artifact>
)

// --- Retrofit Interface ---

interface GitHubApi {

    @GET("user")
    suspend fun getUser(): GitHubUser

    @GET("user/repos")
    suspend fun listUserRepos(
        @Query("per_page") perPage: Int = 100,
        @Query("page") page: Int = 1,
        @Query("sort") sort: String = "updated"
    ): List<GitHubRepo>

    @GET("search/repositories")
    suspend fun searchRepos(
        @Query("q") query: String,
        @Query("per_page") perPage: Int = 100,
        @Query("page") page: Int = 1
    ): RepoSearchResponse

    data class RepoSearchResponse(val items: List<GitHubRepo>)

    @GET("repos/{owner}/{repo}")
    suspend fun getRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): GitHubRepo

    @GET("repos/{owner}/{repo}/git/ref/heads/{branch}")
    suspend fun getBranchRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String
    ): BranchRefResponse

    data class BranchRefResponse(val `object`: RefObject)
    data class RefObject(val sha: String, val type: String, val url: String)

    @GET("repos/{owner}/{repo}/git/trees/{tree_sha}")
    suspend fun getRepoTree(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("tree_sha") treeSha: String,
        @Query("recursive") recursive: Int? = null
    ): GitTreeResponse

    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getFileContent(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Query("ref") ref: String = "main"
    ): FileContentResponse

    @GET("repos/{owner}/{repo}/actions/workflows")
    suspend fun listWorkflows(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): WorkflowsResponse

    @POST("repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches")
    @Headers("Accept: application/vnd.github.v3+json")
    suspend fun dispatchWorkflow(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("workflow_id") workflowId: Long,
        @Body payload: WorkflowDispatchPayload
    ): retrofit2.Response<okhttp3.ResponseBody>

    data class WorkflowDispatchPayload(@SerializedName("ref") val ref: String)

    @GET("repos/{owner}/{repo}/actions/workflows/{workflow_id}/runs")
    suspend fun listWorkflowRuns(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("workflow_id") workflowId: Long,
        @Query("status") status: String? = null,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1
    ): WorkflowRunsResponse

    @GET("repos/{owner}/{repo}/actions/runs/{run_id}/jobs")
    suspend fun getRunJobs(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long
    ): JobsResponse

    @GET("repos/{owner}/{repo}/actions/jobs/{job_id}/logs")
    suspend fun getJobLog(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("job_id") jobId: Long
    ): retrofit2.Response<okhttp3.ResponseBody>

    @GET("repos/{owner}/{repo}/actions/runs/{run_id}/artifacts")
    suspend fun listRunArtifacts(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long
    ): ArtifactsResponse

    // Repository zipball download
    @GET("repos/{owner}/{repo}/zipball/{ref}")
    suspend fun getRepoZipball(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("ref") ref: String
    ): retrofit2.Response<okhttp3.ResponseBody>
}

// --- Network Client Setup ---

class AuthInterceptor(private val token: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): OkHttpResponse {
        val original = chain.request()
        val requestBuilder = original.newBuilder()
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
        val request = requestBuilder.build()
        return chain.proceed(request)
    }
}

object GitHubHttpClient {
    private var retrofit: Retrofit? = null
    private var currentToken: String? = null

    @Synchronized
    fun getClient(token: String): GitHubApi {
        if (retrofit == null || currentToken != token) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(token))
                .addInterceptor(logging)
                .followRedirects(false) // 禁用自动重定向，我们手动处理
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(Constants.GITHUB_BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            currentToken = token
        }
        return retrofit!!.create(GitHubApi::class.java)
    }

    @Synchronized
    fun getPlainClient(token: String? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
        if (token != null) {
            builder.addInterceptor(AuthInterceptor(token))
        }
        return builder.build()
    }

    fun encodePath(path: String): String {
        return URLEncoder.encode(path, StandardCharsets.UTF_8.toString())
    }
}
