package com.app.bugapp

import android.media.RingtoneManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.app.bugapp.ui.theme.BugappTheme
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


@DelicateCoroutinesApi
class MainActivity : ComponentActivity() {
    private val context by lazy { application.baseContext }
    private lateinit var adbConnection: AdbConnection
    private val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        adbConnection = AdbConnection(context)
        val notification = RingtoneManager.getRingtone(context, defaultSoundUri)

        setContent {
            BugappTheme {

                val mPrefs = getSharedPreferences("label", 0)
                val mAddress = mPrefs.getString("address", "")

                val address = remember { mutableStateOf(mAddress.toString()) }
                val port = remember { mutableStateOf(5555) }
                val statusText = remember { mutableStateOf("") }
                val bugButtonEnabled = remember { mutableStateOf(true) }

                Column (
                    verticalArrangement = Arrangement.Top,
                    horizontalAlignment = Alignment.Start,
                    modifier = Modifier.padding(10.dp),
                        ) {

                    OutlinedTextField(
                        value = address.value,
                        singleLine = true,
                        onValueChange = {address.value = it
                        },
                        label = { Text(text = "IP address") },
                    )
                    OutlinedTextField(
                        value = port.value.toString(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        onValueChange = {port.value = it.toInt()
                        },
                        label = { Text(text = "Port") },
                    )
                    Button(
                        enabled = bugButtonEnabled.value,
                        onClick = {
                            if (bugButtonEnabled.value) {
                                bugButtonEnabled.value = false
                                if ( adbConnection.connect(address.value, port.value) ) {
                                    GlobalScope.launch(Dispatchers.IO) {
                                        val mEditor = mPrefs.edit()
                                        mEditor.putString("address", address.value).commit()
                                        statusText.value =
                                            statusText.value + "Collecting bugreport...\n"

                                        val (bugStatus, bugFileName) = adbConnection.bugreport()
                                        if (bugStatus) {
                                            statusText.value =
                                                statusText.value + "Success, bugreport $bugFileName saved to Downloads/bugapp\n"
                                            notification.play()
                                        }
                                        else {
                                            statusText.value =
                                                statusText.value + "Error, could not collect bugreport\n"
                                        }
                                        bugButtonEnabled.value = true
                                    }
                                }
                                else {
                                    statusText.value =
                                        statusText.value + "Error, could not connect to host\n"
                                    bugButtonEnabled.value = true
                                }
                            }
                        },
                    ) {
                        Text(text = "Take bugreport")
                    }
                    OutlinedTextField(
                        value = statusText.value,
                        readOnly = true,
                        onValueChange = {statusText.value = it
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = { Text(text = "") },
                    )

                }


            }
        }
    }
}