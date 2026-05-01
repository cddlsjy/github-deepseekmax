package com.gemini.githubexplorer.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.gemini.githubexplorer.data.github.GitHubHttpClient
import com.gemini.githubexplorer.data.github.GitHubRepository
import com.gemini.githubexplorer.data.github.GitHubUser
import com.gemini.githubexplorer.ui.login.LoginScreen
import com.gemini.githubexplorer.ui.login.LoginViewModel
import com.gemini.githubexplorer.ui.login.LoginViewModelFactory
import com.gemini.githubexplorer.ui.main.MainScreen
import com.gemini.githubexplorer.ui.main.MainViewModel
import com.gemini.githubexplorer.ui.theme.GitHubExplorerTheme
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gemini.githubexplorer.ConfigManager
import com.gemini.githubexplorer.AppConfig

class MainActivity : ComponentActivity() {

    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(this)

        setContent {
            GitHubExplorerTheme {
                val coroutineScope = rememberCoroutineScope()
                var loggedInUser by remember { mutableStateOf<GitHubUser?>(null) }
                var gitHubRepository by remember { mutableStateOf<GitHubRepository?>(null) }
                var initialConfig by remember { mutableStateOf<AppConfig?>(null) }
                var showLogin by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    val config = configManager.loadConfig()
                    initialConfig = config
                    val token = config.token
                    if (token.isNotBlank()) {
                        try {
                            val api = GitHubHttpClient.getClient(token)
                            val repository = GitHubRepository(api, GitHubHttpClient.getPlainClient(token))
                            val user = repository.getUser()
                            gitHubRepository = repository
                            loggedInUser = user
                            showLogin = false
                        } catch (e: Exception) {
                            showLogin = true
                        }
                    }
                }

                if (showLogin) {
                    val loginViewModel: LoginViewModel = viewModel(
                        factory = LoginViewModelFactory(LocalContext.current)
                    )
                    LoginScreen(
                        viewModel = loginViewModel,
                        onLoginSuccess = { repo, user ->
                            gitHubRepository = repo
                            loggedInUser = user
                            showLogin = false
                            coroutineScope.launch {
                                initialConfig = configManager.loadConfig()
                            }
                        }
                    )
                } else if (gitHubRepository != null && loggedInUser != null && initialConfig != null) {
                    val mainViewModel: MainViewModel = viewModel(
                        factory = MainViewModel.Factory(
                            LocalContext.current,
                            gitHubRepository!!,
                            initialConfig!!
                        )
                    )
                    MainScreen(viewModel = mainViewModel)
                }
            }
        }
    }
}
