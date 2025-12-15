package com.app.bugapp

import android.content.SharedPreferences
import android.media.RingtoneManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import com.app.bugapp.ui.theme.BugappTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val context by lazy { applicationContext }
    private lateinit var adbConnection: AdbConnection
    private val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()


        adbConnection = AdbConnection(context)
        val notification = RingtoneManager.getRingtone(context, defaultSoundUri)

        setContent {
            BugappTheme {
                val mPrefs = getSharedPreferences("bugapp_prefs", 0)
                val mAddress = mPrefs.getString("address", "")
                val mTimeout = mPrefs.getInt("timeout", 50)

                val address = remember { mutableStateOf(mAddress.toString()) }
                val port = remember { mutableIntStateOf(5555) }
                val timeout = remember { mutableIntStateOf(mTimeout) }
                val statusText = remember { mutableStateOf("") }
                val buttonsEnabled = remember { mutableStateOf(true) }
                val loading = remember { mutableStateOf(false) }
                val showSettingsDialog = remember { mutableStateOf(false) }

                Scaffold(modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("Bugapp") },
                            actions = {
                                IconButton(onClick = { showSettingsDialog.value = true }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    val coroutineScope = rememberCoroutineScope()

                    if (showSettingsDialog.value) {
                        SettingsDialog(
                            timeout = timeout,
                            mPrefs = mPrefs,
                            onDismiss = { showSettingsDialog.value = false })
                    }

                    Column(
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.Start,
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(10.dp),
                    ) {

                        OutlinedTextField(
                            value = address.value,
                            singleLine = true,
                            onValueChange = { address.value = it },
                            label = { Text(text = "IP address") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = port.intValue.toString().trim(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            onValueChange = { port.intValue = it.toIntOrNull() ?: 5555 },
                            label = { Text(text = "Port") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            Button(
                                enabled = buttonsEnabled.value,
                                onClick = {
                                    if (buttonsEnabled.value) {
                                        buttonsEnabled.value = false
                                        loading.value = true
                                        coroutineScope.launch(Dispatchers.IO) {
                                            if (adbConnection.connect(address.value, port.intValue)) {
                                                mPrefs.edit { putString("address", address.value) }
                                                withContext(Dispatchers.Main) {
                                                    statusText.value += "Collecting bugreport...\n"
                                                }

                                                val (bugStatus, bugFileName) = adbConnection.bugreport()
                                                withContext(Dispatchers.Main) {
                                                    if (bugStatus) {
                                                        statusText.value += "Success, bugreport saved to $bugFileName\n"
                                                        notification.play()
                                                    } else {
                                                        statusText.value += "Error, could not collect bugreport\n"
                                                    }
                                                    loading.value = false
                                                    buttonsEnabled.value = true
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    statusText.value += "Error, could not connect to host\n"
                                                    buttonsEnabled.value = true
                                                    loading.value = false
                                                }
                                            }
                                        }
                                    }
                                },
                            ) {
                                Text(text = "Take bugreport")
                            }
                            Button(
                                modifier = Modifier.padding(start = 10.dp),
                                enabled = buttonsEnabled.value,
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        withContext(Dispatchers.Main) {
                                            buttonsEnabled.value = false
                                            statusText.value = ""
                                        }

                                        val foundIp = adbConnection.scanForDevices(port.intValue, timeout.intValue) { progress ->
                                            coroutineScope.launch(Dispatchers.Main) {
                                                statusText.value += progress
                                            }
                                        }

                                        withContext(Dispatchers.Main) {
                                            if (foundIp != null) {
                                                address.value = foundIp
                                            }
                                            buttonsEnabled.value = true
                                        }
                                    }
                                },
                            ) {
                                Text(text = "Scan for devices")
                            }
                        }


                        if (loading.value) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                        OutlinedTextField(
                            value = statusText.value,
                            readOnly = true,
                            onValueChange = { },
                            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                            label = { Text(text = "") },
                        )

                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(timeout: MutableIntState, mPrefs: SharedPreferences, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Settings", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = timeout.intValue.toString().trim(),
                    onValueChange = {
                        val newTimeout = it.toIntOrNull() ?: 50
                        timeout.intValue = newTimeout
                        mPrefs.edit { putInt("timeout", newTimeout) }
                    },
                    label = { Text(text = "Scan Timeout (ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onDismiss) {
                    Text("Done")
                }
            }
        }
    }
}
