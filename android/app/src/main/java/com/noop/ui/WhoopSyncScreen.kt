package com.noop.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.noop.sync.SyncKeyStore
import com.noop.sync.SyncPrefs
import com.noop.sync.WhoopSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 1 of the WHOOP -> open-wearables bridge (`docs/specs/2026-07-04-whoop-android-collector-design.md`).
 * Connection settings (backend URL, open-wearables user id, API key — see [SyncKeyStore]) plus the
 * periodic-sync toggle/interval and a manual "Sync now" ([WhoopSync]). Mirrors [BackupSyncScreen]'s
 * shape: a settings card, then an action card with a status line.
 *
 * Only raw HR + R-R interval samples are synced — see [WhoopSync]'s own doc for why NOOP's derived
 * scores are deliberately left out.
 */
@Composable
fun WhoopSyncScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(SyncKeyStore.readBaseUrl(context)) }
    var userId by remember { mutableStateOf(SyncKeyStore.readUserId(context)) }
    var apiKey by remember { mutableStateOf(SyncKeyStore.readApiKey(context)) }

    var enabled by remember { mutableStateOf(SyncPrefs.enabled(context)) }
    var lastSyncMs by remember { mutableStateOf(SyncPrefs.lastSyncMs(context)) }
    var busy by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf<String?>(null) }

    val configured = baseUrl.isNotBlank() && userId.isNotBlank() && apiKey.isNotBlank()

    LazyScreenScaffold(
        title = "Sync to open-wearables",
        subtitle = "Push raw heart rate + R-R interval samples from this strap to your self-hosted " +
            "open-wearables backend, so its own algorithms compute HRV/recovery from data you own.",
    ) {
        // 1 · Connection
        item {
            NoopCard(padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Backend connection", style = NoopType.headline, color = Palette.textPrimary)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Overline("Backend URL")
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    "http://<your-backend-host>:8000",
                                    style = NoopType.body, color = Palette.textTertiary,
                                )
                            },
                            textStyle = NoopType.mono(13f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = syncFieldColors(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Overline("open-wearables User ID")
                        OutlinedTextField(
                            value = userId,
                            onValueChange = { userId = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text("UUID from the open-wearables Create User API", style = NoopType.body, color = Palette.textTertiary)
                            },
                            textStyle = NoopType.mono(13f),
                            singleLine = true,
                            colors = syncFieldColors(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Overline("API Key")
                        var showKey by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("sk-…", style = NoopType.body, color = Palette.textTertiary) },
                            textStyle = NoopType.mono(13f),
                            singleLine = true,
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            colors = syncFieldColors(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            trailingIcon = {
                                Text(
                                    if (showKey) "Hide" else "Show",
                                    style = NoopType.caption,
                                    color = Palette.accent,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .clickable { showKey = !showKey },
                                )
                            },
                        )
                    }

                    NoopButton(
                        text = "Save connection",
                        leadingIcon = Icons.Filled.Sync,
                        kind = NoopButtonKind.Secondary,
                        fullWidth = true,
                        onClick = {
                            SyncKeyStore.saveBaseUrl(context, baseUrl)
                            SyncKeyStore.saveUserId(context, userId)
                            SyncKeyStore.saveApiKey(context, apiKey)
                            runCatching { WhoopSync.reschedule(context) }
                            Toast.makeText(context, "Connection saved.", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }

        // 2 · Sync schedule + manual trigger
        item {
            NoopCard(padding = 20.dp, tint = if (enabled) Palette.accent else null) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("Automatic sync", style = NoopType.body, color = Palette.textPrimary)
                            Text(
                                "Syncs roughly every ${SyncPrefs.intervalMinutes(context)} minutes while on " +
                                    "any network reachable to your backend (e.g. Tailscale). Off by default.",
                                style = NoopType.footnote, color = Palette.textTertiary,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = enabled,
                            enabled = configured && !busy,
                            onCheckedChange = {
                                enabled = it
                                SyncPrefs.setEnabled(context, it)
                                runCatching { WhoopSync.reschedule(context) }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Palette.surfaceBase,
                                checkedTrackColor = Palette.accent,
                                uncheckedThumbColor = Palette.textSecondary,
                                uncheckedTrackColor = Palette.surfaceInset,
                                uncheckedBorderColor = Palette.hairline,
                            ),
                        )
                    }

                    Text(
                        if (lastSyncMs > 0L) {
                            "Last sync: ${DateUtils.getRelativeTimeSpanString(lastSyncMs)}"
                        } else {
                            "Never synced yet."
                        },
                        style = NoopType.caption, color = Palette.textTertiary,
                    )
                    statusLine?.let {
                        Text(it, style = NoopType.caption, color = Palette.textSecondary)
                    }

                    NoopButton(
                        text = if (busy) "Syncing…" else "Sync now",
                        leadingIcon = Icons.Filled.Sync,
                        fullWidth = true,
                        enabled = configured && !busy,
                        onClick = {
                            busy = true
                            statusLine = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { WhoopSync.runOnce(context) }
                                lastSyncMs = SyncPrefs.lastSyncMs(context)
                                busy = false
                                statusLine = result.message
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            }
                        },
                    )

                    if (!configured) {
                        Text(
                            "Set the backend URL, user id, and API key above first.",
                            style = NoopType.caption, color = Palette.statusWarning,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun syncFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.textPrimary,
    unfocusedTextColor = Palette.textPrimary,
    focusedBorderColor = Palette.accent,
    unfocusedBorderColor = Palette.hairline,
    cursorColor = Palette.accent,
)
