package com.mylittleworld.child.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.mylittleworld.child.ChildApp
import com.mylittleworld.child.R
import com.mylittleworld.child.service.FamilyConnectService
import com.mylittleworld.child.ui.theme.MyLittleWorldTheme
import com.mylittleworld.child.ui.theme.Palette
import com.mylittleworld.shared.logic.PairingCode
import com.mylittleworld.shared.model.ClaimRequest
import com.mylittleworld.shared.net.ApiException
import com.mylittleworld.shared.util.AppLog
import kotlinx.coroutines.launch

/**
 * Child-side pairing. The parent must first create a short-lived, single-use code
 * in the parent app; this screen only exchanges it for a device credential.
 */
class PairingActivity : ComponentActivity() {

    private val container get() = (application as ChildApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyLittleWorldTheme {
                var code by remember { mutableStateOf("") }
                var server by remember { mutableStateOf("") }
                LaunchedEffect(Unit) { server = container.settings.getServerUrl() }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var success by remember { mutableStateOf<String?>(null) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.pair_title),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = Palette.Ink,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.pair_help),
                        fontSize = 15.sp,
                        color = Palette.InkSoft,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text(stringResource(R.string.settings_server)) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }.take(6)
                            code = digits
                            error = null
                        },
                        label = { Text(stringResource(R.string.pair_code)) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = Color(0xFFC0392B), fontSize = 14.sp, textAlign = TextAlign.Center)
                    }
                    success?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = Palette.CallEnd, fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = {
                            val normalised = PairingCode.normalise(code)
                            if (normalised == null) {
                                error = "Enter the 6 numbers from the parent app."
                                return@Button
                            }
                            busy = true
                            error = null
                            lifecycleScope.launch {
                                if (server.isNotBlank()) container.settings.saveServerUrl(server)
                                try {
                                    val claim = container.childApi().claim(
                                        ClaimRequest(
                                            code = normalised,
                                            deviceName = container.deviceName(),
                                            platform = "android",
                                            appVersion = container.appVersion(),
                                        ),
                                    )
                                    container.secure.token = claim.deviceToken
                                    container.secure.parentName = claim.parentName
                                    container.settings.saveDevice(claim.deviceId, claim.parentName)
                                    container.settings.saveConfig(claim.config)
                                    success = getString(R.string.pair_success, claim.parentName)
                                    FamilyConnectService.start(this@PairingActivity)
                                } catch (e: ApiException) {
                                    AppLog.w("PairingActivity", "Pairing failed: ${e.kind}")
                                    error = when (e.httpCode) {
                                        410, 410 -> getString(R.string.pair_expired)
                                        else -> e.friendly
                                    }
                                } catch (e: Exception) {
                                    AppLog.w("PairingActivity", "Pairing failed", e)
                                    error = getString(R.string.pair_failed)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && code.length == 6,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.CallEnd),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(22.dp),
                                color = Color.White,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Text(stringResource(R.string.pair_action), fontSize = 18.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { finish() }) {
                        Text(stringResource(R.string.learning_go_back), color = Palette.InkSoft)
                    }
                }
            }
        }
    }
}
