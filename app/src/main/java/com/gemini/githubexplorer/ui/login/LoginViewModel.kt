package com.gemini.githubexplorer.ui.login

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gemini.githubexplorer.ConfigManager
import com.gemini.githubexplorer.data.github.GitHubHttpClient
import com.gemini.githubexplorer.data.github.GitHubRepository
import com.gemini.githubexplorer.data.github.GitHubUser
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

class LoginViewModel(private val configManager: ConfigManager, private val context: Context) : ViewModel() {

    val token = mutableStateOf("")
    val showToken = mutableStateOf(false)
    val isLoading = mutableStateOf(false)
    val errorMessage = mutableStateOf<String?>(null)
    val userLoggedIn = mutableStateOf<GitHubUser?>(null)

    init {
        viewModelScope.launch {
            token.value = configManager.loadConfig().token
        }
    }

    fun toggleShowToken() {
        showToken.value = !showToken.value
    }

    fun login() {
        val currentToken = token.value.trim()
        if (currentToken.isBlank()) {
            errorMessage.value = "Token cannot be empty."
            return
        }

        isLoading.value = true
        errorMessage.value = null

        viewModelScope.launch {
            try {
                val api = GitHubHttpClient.getClient(currentToken)
                val repository = GitHubRepository(api, GitHubHttpClient.getPlainClient(currentToken))
                val user = repository.getUser()
                configManager.saveToken(currentToken)
                userLoggedIn.value = user
            } catch (e: HttpException) {
                val errorBody = e.response()?.errorBody()?.string()
                errorMessage.value = "API Error ${e.code()}: ${errorBody ?: e.message}"
            } catch (e: IOException) {
                errorMessage.value = "Network Error: ${e.message}"
            } catch (e: Exception) {
                errorMessage.value = "An unexpected error occurred: ${e.message}"
            } finally {
                isLoading.value = false
            }
        }
    }
}

class LoginViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LoginViewModel(ConfigManager(context), context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
