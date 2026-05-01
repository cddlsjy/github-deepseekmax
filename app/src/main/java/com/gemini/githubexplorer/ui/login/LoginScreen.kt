package com.gemini.githubexplorer.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gemini.githubexplorer.data.github.GitHubHttpClient
import com.gemini.githubexplorer.data.github.GitHubRepository
import com.gemini.githubexplorer.data.github.GitHubUser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: (GitHubRepository, GitHubUser) -> Unit
) {
    val token by viewModel.token
    val showToken by viewModel.showToken
    val isLoading by viewModel.isLoading
    val errorMessage by viewModel.errorMessage
    val userLoggedIn by viewModel.userLoggedIn

    LaunchedEffect(userLoggedIn) {
        userLoggedIn?.let { user ->
            val api = GitHubHttpClient.getClient(token)
            val repository = GitHubRepository(api, GitHubHttpClient.getPlainClient(token))
            onLoginSuccess(repository, user)
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.errorMessage.value = null },
            title = { Text("Error") },
            text = { Text(errorMessage!!) },
            confirmButton = {
                Button(onClick = { viewModel.errorMessage.value = null }) {
                    Text("OK")
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Login to GitHub",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = token,
                onValueChange = { viewModel.token.value = it },
                label = { Text("Personal Access Token") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    val image = if (showToken) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    IconButton(onClick = viewModel::toggleShowToken) {
                        Icon(imageVector = image, contentDescription = "Toggle password visibility")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = viewModel::login,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Login")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Please enter your GitHub Personal Access Token",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
