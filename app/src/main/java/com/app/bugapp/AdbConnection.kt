package com.app.bugapp

import android.content.Context
import android.os.Environment
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(DelicateCoroutinesApi::class)
class AdbConnection (private val context: Context)  {
    private lateinit var manager: Dadb
    private val tag : String? = AdbConnection::class.simpleName


    fun connect(host: String, port: Int): Boolean {
        var handler: Dadb? = null
        val running = GlobalScope.async {
            handler = Dadb.create(host, port,  keygen())
        }

        Thread.sleep(1000)
        running.cancel()
        if (handler == null) {
            Log.w(tag, "Please accept connection request on host device")
            return false
        }
        manager = handler as Dadb
        return true
    }

    private suspend fun keygen(): AdbKeyPair = withContext(IO) {
        val cacheDir = context.cacheDir
        val privateCert = File(cacheDir, "adbkey")
        val pubCert = File(cacheDir, "adbkey.pub")

        if (!privateCert.exists() || !pubCert.exists() ) {
            AdbKeyPair.generate(privateCert, pubCert)
        }
        AdbKeyPair.read(privateCert, pubCert)

    }

    fun bugreport(): Pair<Boolean, String> {
        Log.w(tag, "Starting bug report..")

        val downloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val buggappDirectory = File("$downloadsPath/bugapp")
        if ( ! buggappDirectory.exists() ) {
            Log.w(tag, "Creating bugapp folder in Downloads/")
            val letDirectory = File(downloadsPath, "bugapp")
            letDirectory.mkdirs()
        }

        try {
            val response = manager.shell("bugreportz")
            assert(response.exitCode == 0)
            Log.w(tag, "Bugreportz done..:")
            if (response.output.contains("OK:")) {
                val remoteFilename = response.output.split("OK:")[1].trim()
                val localFilename = remoteFilename.substringAfterLast("/")
                val file = File(buggappDirectory, localFilename)
                val isCreated = file.createNewFile()
                if (isCreated) {
                    manager.pull(file, remoteFilename)
                }
                manager.shell("rm $remoteFilename")
                return Pair(true, localFilename)
            }
        }
        catch (ignore : Throwable) { }
        return Pair(false, "")
    }
}