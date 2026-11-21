package com.mylittleworld.parent.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.mylittleworld.parent.ParentApp
import com.mylittleworld.parent.R
import com.mylittleworld.parent.service.ParentCallService
import com.mylittleworld.parent.ui.theme.ParentPalette
import com.mylittleworld.parent.ui.theme.ParentTheme
import com.mylittleworld.shared.net.ApiException
import com.mylittleworld.shared.util.AppLog
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {

    private val container get() = (application as ParentApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (container.signedIn.value) {
            openDashboard()
            finish()
            return
        }
        setContent {
            ParentTheme {
                var server by remember { mutableStateOf(container.prefs.serverUrl) }
                var email by remember { mutableStateOf(container.prefs.email ?: "") }
                var password by remember { mutableStateOf("") }
                var name by remember { mutableStateOf("") }
                var registering by remember { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }

                fun submit() {
                    if (email.isBlank() || password.length < 8) {
                        error = "Enter your email and a password of at least 8 characters."
                        return
                    }
                    busy = true
                    error = null
                    container.prefs.serverUrl = server
                    lifecycleScope.launch {
                        try {
                            val api = container.api()
                            val session = if (registering) {
                                api.register(email.trim(), password, name.trim().ifBlank { "Parent" })
                            } else {
                                api.login(email.trim(), password)
                            }
                            container.onSignedIn(
                                session.token, session.expiresAt, session.parent.name, session.parent.email,
                            )
                            ParentCallService.start(this@LoginActivity)
                            openDashboard()
                            finish()
                        } catch (e: ApiException) {
                            AppLog.w("LoginActivity", "Sign in failed: ${e.kind}")
                            error = if (e.kind == com.mylittleworld.shared.net.ApiErrorKind.NETWORK) {
                                getString(R.string.error_offline)
                            } else {
                                e.friendly
                            }
                        } catch (e: Exception) {
                            AppLog.w("LoginActivity", "Sign in failed", e)
                            error = getString(R.string.login_failed)
                        } finally {
                            busy = false
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.login_title),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = ParentPalette.Ink,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.login_subtitle),
                        fontSize = 15.sp,
                        color = ParentPalette.InkMuted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(26.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ParentPalette.Card),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            OutlinedTextField(
                                value = server,
                                onValueChange = { server = it },
                                label = { Text(stringResource(R.string.login_server)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text(stringResource(R.string.login_email)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.login_password)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (registering) {
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    label = { Text(stringResource(R.string.register_name)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            error?.let {
                                Spacer(Modifier.height(10.dp))
                                Text(it, color = ParentPalette.Danger, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(18.dp))
                            Button(
                                onClick = { submit() },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ParentPalette.Accent),
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.height(20.dp),
                                    )
                                } else {
                                    Text(
                                        stringResource(
                                            if (registering) R.string.register_action else R.string.login_action,
                                        ),
                                        fontSize = 16.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = {
                                registering = !registering
                                error = null
                            }) {
                                Text(
                                    stringResource(
                                        if (registering) R.string.login_action else R.string.login_register,
                                    ),
                                    color = ParentPalette.Accent,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.register_hint),
                        fontSize = 12.sp,
                        color = ParentPalette.InkMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    private fun openDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
    }
}
