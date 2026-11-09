package com.swapnil.mvilogin.ui.login

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.swapnil.mvilogin.domain.model.AppError
import com.swapnil.mvilogin.domain.model.UsernameError
import com.swapnil.mvilogin.domain.model.PasswordStrength
import kotlinx.coroutines.launch

/**
 * STATEFUL "route": wires the ViewModel to the stateless screen.
 * Only this function knows about Hilt / ViewModel / lifecycle.
 */
@Composable
fun LoginRoute(
    onLoggedIn: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    // collectAsStateWithLifecycle: stops collecting when the app goes below STARTED
    // (background), unlike collectAsState which keeps collecting and wastes work.
    val state by viewModel.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // rememberCoroutineScope: a scope tied to this composable, for launching from callbacks.
    // Used so a long showSnackbar() doesn't block the effect collector below.
    val scope = rememberCoroutineScope()

    // rememberUpdatedState: the long-running effect below always calls the LATEST lambda
    // without having to restart the effect when the caller passes a new lambda.
    val currentOnLoggedIn by rememberUpdatedState(onLoggedIn)
    val lifecycleOwner = LocalLifecycleOwner.current

    // LaunchedEffect: coroutine tied to composition; restarts only if its keys change.
    // repeatOnLifecycle(STARTED): collect effects only while visible; Channel keeps them buffered meanwhile.
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    LoginEffect.NavigateToHome -> currentOnLoggedIn()
                    is LoginEffect.ShowMessage -> scope.launch { snackbarHostState.showSnackbar(effect.message) }
                }
            }
        }
    }

    // A stable function reference: avoids creating a new lambda instance on each recomposition
    // (strong skipping in Kotlin 2.0.20+ memoizes this for you, but being explicit is clearer).
    val onIntent = remember(viewModel) { viewModel::onIntent }

    LoginScreen(state = state, snackbarHostState = snackbarHostState, onIntent = onIntent)
}

/**
 * STATELESS screen: (state, onIntent) -> UI. No ViewModel, no Hilt.
 * => Previewable, screenshot-testable, reusable. This is "state hoisting".
 */
@Composable
fun LoginScreen(
    state: LoginState,
    snackbarHostState: SnackbarHostState,
    onIntent: (LoginIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val passwordMask = remember { PasswordVisualTransformation() } // allocate once

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(40.dp))
            Text("Welcome back", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Sign in to continue",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!state.isOnline) OfflineBanner()

            OutlinedTextField(
                value = state.username,
                onValueChange = { onIntent(LoginIntent.UsernameChanged(it)) },
                label = { Text("Username") },
                singleLine = true,
                enabled = !state.isLoading,
                isError = state.usernameError != null,
                supportingText = if (state.usernameError != null) {
                    { Text(state.usernameError.toMessage()) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = { onIntent(LoginIntent.PasswordChanged(it)) },
                label = { Text("Password") },
                singleLine = true,
                enabled = !state.isLoading,
                visualTransformation = if (state.isPasswordVisible) VisualTransformation.None else passwordMask,
                trailingIcon = {
                    TextButton(onClick = { onIntent(LoginIntent.TogglePasswordVisibility) }) {
                        Text(if (state.isPasswordVisible) "Hide" else "Show")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    onIntent(LoginIntent.Submit)
                }),
                modifier = Modifier.fillMaxWidth(),
            )

            PasswordStrengthMeter(state.passwordStrength)

            val status = state.status // local val -> smart cast works
            if (status is LoginStatus.Error) {
                Text(
                    text = status.error.toMessage(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Button(
                onClick = {
                    focusManager.clearFocus()
                    onIntent(LoginIntent.Submit)
                },
                enabled = state.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                } else {
                    Text("Sign in")
                }
            }

            DemoHints(onUseDemoAccount = { onIntent(LoginIntent.FillDemoCredentials) })
        }
    }
}

@Composable
private fun PasswordStrengthMeter(strength: PasswordStrength) {
    if (strength == PasswordStrength.EMPTY) return
    val progress by animateFloatAsState(targetValue = strength.progress, label = "strength")
    // Exhaustive `when` over an enum: compiler forces us to handle every level.
    val color = when (strength) {
        PasswordStrength.EMPTY, PasswordStrength.WEAK -> MaterialTheme.colorScheme.error
        PasswordStrength.MEDIUM -> Color(0xFFE09B00)
        PasswordStrength.STRONG -> Color(0xFF2E7D32)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Lambda overload: progress is read in the draw phase only -> no recomposition per frame.
        LinearProgressIndicator(progress = { progress }, color = color, modifier = Modifier.fillMaxWidth())
        Text("Strength: ${strength.label}", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun OfflineBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "You're offline — sign-in is disabled until you reconnect.",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun DemoHints(onUseDemoAccount: () -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            "Live API: dummyjson.com · demo account ${DemoCredentials.USERNAME} / ${DemoCredentials.PASSWORD}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onUseDemoAccount) { Text("Use demo account") }
    }
}

// ───────────── Previews: possible only because LoginScreen is stateless ─────────────

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    MaterialTheme {
        LoginScreen(
            state = LoginState(
                username = "em",
                password = "Pass1",
                usernameError = UsernameError.TOO_SHORT,
                passwordStrength = PasswordStrength.WEAK,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onIntent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenErrorOfflinePreview() {
    MaterialTheme {
        LoginScreen(
            state = LoginState(
                username = "test",
                password = "test1234",
                passwordStrength = PasswordStrength.WEAK,
                isOnline = false,
                status = LoginStatus.Error(AppError.InvalidCredentials),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onIntent = {},
        )
    }
}
