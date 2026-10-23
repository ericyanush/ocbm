package com.carlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.ocbm.VehicleHotspotStore
import com.carlink.ui.components.ControlCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun HotspotTabContent(carlinkManager: CarlinkManager) {
    val context = LocalContext.current
    val store = remember(context) { VehicleHotspotStore(context) }
    val saved = remember(store) { store.fields() }
    var ssid by remember { mutableStateOf(saved.ssid) }
    var password by remember { mutableStateOf(saved.password) }
    var channel by remember { mutableStateOf(saved.channel) }
    var security by remember { mutableStateOf(saved.security) }
    var passwordVisible by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ControlCard(title = "Vehicle hotspot", icon = Icons.Default.Wifi) {
            Text(
                "These credentials are sent to the adapter before wireless starts. The adapter then " +
                    "hands them to the iPhone for the CarPlay Wi-Fi connection. Its own access point " +
                    "stays disabled. Enter the truck hotspot's current SSID, password, and channel.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = ssid,
                onValueChange = {
                    ssid = it
                    message = null
                },
                label = { Text("Hotspot network name (SSID)") },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    message = null
                },
                label = { Text("Hotspot password") },
                singleLine = true,
                enabled = !saving,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    androidx.compose.material3.TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(if (passwordVisible) "Hide" else "Show")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            OutlinedTextField(
                value = channel,
                onValueChange = {
                    channel = it.filter(Char::isDigit).take(3)
                    message = null
                },
                label = { Text("Wi-Fi channel") },
                supportingText = { Text("Use the channel shown in the truck hotspot settings (for example, 36).") },
                singleLine = true,
                enabled = !saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text("Hotspot security", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(2 to "WPA2", 3 to "WPA2/WPA3", 4 to "WPA3").forEach { (value, label) ->
                    FilterChip(selected = security == value, onClick = { security = value },
                        enabled = !saving, label = { Text(label) })
                }
            }
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val error =
                            withContext(Dispatchers.IO) {
                                store.save(VehicleHotspotStore.Fields(ssid, password, channel, security))
                            }
                        if (error != null) {
                            message = error
                        } else {
                            message = "Saved. Restarting the adapter session with the vehicle hotspot settings…"
                            withContext(Dispatchers.IO) { carlinkManager.start() }
                        }
                        saving = false
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text(if (saving) "Saving…" else "Save and reconnect")
            }
        }
    }
}
