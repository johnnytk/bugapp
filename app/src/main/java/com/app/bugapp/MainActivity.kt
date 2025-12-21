package com.app.bugapp

import android.content.Intent
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.app.bugapp.ui.theme.BugappTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val context by lazy { applicationContext }
    private lateinit var adbConnection: AdbConnection
    private val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun getBugReportDir(): File {
        val downloadsPath = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        return File(downloadsPath, "bugapp")
    }

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
                val mConcurrency = mPrefs.getInt("concurrency", 5)

                val address = remember { mutableStateOf(mAddress.toString()) }
                val port = remember { mutableIntStateOf(5555) }
                val timeout = remember { mutableIntStateOf(mTimeout) }
                val concurrency = remember { mutableIntStateOf(mConcurrency) }
                val statusText = remember { mutableStateOf("") }
                val buttonsEnabled = remember { mutableStateOf(true) }
                val loading = remember { mutableStateOf(false) }
                val showSettingsDialog = remember { mutableStateOf(false) }
                val scanning = remember { mutableStateOf(false) }
                val scanProgress = remember { mutableFloatStateOf(0f) }
                val fileList = remember { mutableStateListOf<File>() }
                val downloadingFile = remember { mutableStateOf<String?>(null) }
                val selectedTab = remember { mutableIntStateOf(0) }

                fun refreshFiles() {
                    val dir = getBugReportDir()
                    if (dir.exists()) {
                        fileList.clear()
                        fileList.addAll(dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList())
                    }
                }

                LaunchedEffect(Unit) {
                    refreshFiles()
                }

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
                            concurrency = concurrency,
                            mPrefs = mPrefs,
                            onDismiss = { showSettingsDialog.value = false })
                    }

                    Column(
                        modifier = Modifier.padding(innerPadding),
                    ) {
                        TabRow(selectedTabIndex = selectedTab.intValue) {
                            Tab(
                                selected = selectedTab.intValue == 0,
                                onClick = { selectedTab.intValue = 0 },
                                text = { Text("Collect") }
                            )
                            Tab(
                                selected = selectedTab.intValue == 1,
                                onClick = { selectedTab.intValue = 1 },
                                text = { Text("Reports (${fileList.size})") }
                            )
                        }

                        when (selectedTab.intValue) {
                            0 -> {
                                Column(
                                    verticalArrangement = Arrangement.Top,
                                    horizontalAlignment = Alignment.Start,
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxSize(),
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
                                            modifier = Modifier.weight(1f),
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

                                                            val (bugStatus, bugFileName) = adbConnection.bugreport(
                                                                onDownloadStarted = { fileName ->
                                                                    coroutineScope.launch(Dispatchers.Main) {
                                                                        downloadingFile.value = fileName
                                                                    }
                                                                }
                                                            )
                                                            withContext(Dispatchers.Main) {
                                                                downloadingFile.value = null
                                                                if (bugStatus) {
                                                                    statusText.value += "Success, bugreport saved to $bugFileName\n"
                                                                    notification.play()
                                                                    refreshFiles()
                                                                    selectedTab.intValue = 1
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
                                        Spacer(modifier = Modifier.padding(4.dp))
                                        Button(
                                            modifier = Modifier.weight(1f),
                                            enabled = buttonsEnabled.value,
                                            onClick = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    withContext(Dispatchers.Main) {
                                                        buttonsEnabled.value = false
                                                        scanning.value = true
                                                        statusText.value = ""
                                                        scanProgress.floatValue = 0f
                                                    }

                                                    val foundIp = adbConnection.scanForDevices(
                                                        port.intValue,
                                                        timeout.intValue,
                                                        concurrency.intValue
                                                    ) { (progress, message) ->
                                                        coroutineScope.launch(Dispatchers.Main) {
                                                            scanProgress.floatValue = progress / 254f
                                                            statusText.value = message
                                                        }
                                                    }

                                                    withContext(Dispatchers.Main) {
                                                        if (foundIp != null) {
                                                            address.value = foundIp
                                                        }
                                                        buttonsEnabled.value = true
                                                        scanning.value = false
                                                    }
                                                }
                                            },
                                        ) {
                                            Text(text = "Scan")
                                        }
                                    }


                                    if (scanning.value) {
                                        LinearProgressIndicator(
                                            progress = { scanProgress.floatValue },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 16.dp)
                                        )
                                    } else if (loading.value) {
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 16.dp)
                                        )
                                    }

                                    Text(
                                        text = "Status",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 16.dp)
                                    )

                                    OutlinedTextField(
                                        value = statusText.value,
                                        readOnly = true,
                                        onValueChange = { },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(top = 8.dp),
                                    )
                                }
                            }
                            1 -> {
                                Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                                    Text("Saved Bugreports", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        downloadingFile.value?.let { fileName ->
                                            item {
                                                FileItem(fileName = fileName, isDownloading = true)
                                            }
                                        }
                                        items(fileList) { file ->
                                            FileItem(
                                                fileName = file.name,
                                                isDownloading = false,
                                                onShare = { shareFile(file) },
                                                onDelete = {
                                                    file.delete()
                                                    refreshFiles()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun FileItem(
        fileName: String,
        isDownloading: Boolean,
        onShare: () -> Unit = {},
        onDelete: () -> Unit = {}
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = fileName, style = MaterialTheme.typography.bodyMedium)
                    if (isDownloading) {
                        Text(
                            text = "Downloading...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
                if (!isDownloading) {
                    Row {
                        IconButton(onClick = onShare) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }

    private fun shareFile(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${applicationContext.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Bugreport"))
    }
}

@Composable
fun SettingsDialog(
    timeout: MutableIntState,
    concurrency: MutableIntState,
    mPrefs: SharedPreferences,
    onDismiss: () -> Unit
) {
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
                Text("Settings", style = MaterialTheme.typography.titleLarge)
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
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = concurrency.intValue.toString().trim(),
                    onValueChange = {
                        val newConcurrency = it.toIntOrNull() ?: 5
                        concurrency.intValue = newConcurrency
                        mPrefs.edit { putInt("concurrency", newConcurrency) }
                    },
                    label = { Text(text = "Concurrent Threads") },
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
