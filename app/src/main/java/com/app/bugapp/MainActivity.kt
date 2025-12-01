package com.app.bugapp

import android.media.RingtoneManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.app.bugapp.ui.theme.BugappTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


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

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val mPrefs = getSharedPreferences("label", 0)
                    val mAddress = mPrefs.getString("address", "")
                    val coroutineScope = rememberCoroutineScope()

                    val address = remember { mutableStateOf(mAddress.toString()) }
                    val port = remember { mutableIntStateOf(5555) }
                    val statusText = remember { mutableStateOf("") }
                    val buttonsEnabled = remember { mutableStateOf(true) }
                    val loading = remember { mutableStateOf(false) }
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
                        )
                        OutlinedTextField(
                            value = port.intValue.toString().trim(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            onValueChange = { port.intValue = it.toInt() },
                            label = { Text(text = "Port") },
                        )
                        Row {
                            Button(
                                enabled = buttonsEnabled.value,
                                onClick = {
                                    if (buttonsEnabled.value) {
                                        buttonsEnabled.value = false
                                        loading.value = true
                                        coroutineScope.launch(Dispatchers.IO) {
                                            if (adbConnection.connect(address.value, port.intValue)) {
                                                mPrefs.edit {
                                                    putString("address", address.value)
                                                }
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

                                        val foundIp = adbConnection.scanForDevices(port.intValue) { progress ->
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
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(
                            value = statusText.value,
                            readOnly = true,
                            onValueChange = { },
                            modifier = Modifier.fillMaxSize(),
                            label = { Text(text = "") },
                        )

                    }
                }


            }
        }
    }
}
