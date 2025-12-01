package com.app.bugapp

import android.content.Context
import android.net.ConnectivityManager
import android.os.Environment
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

class AdbConnection(private val context: Context) {
    private lateinit var manager: Dadb
    private val tag: String? = AdbConnection::class.simpleName


    suspend fun connect(host: String, port: Int): Boolean {
        manager = withTimeoutOrNull(1000) {
            Dadb.create(host, port, keygen())
        } ?: run {
            Log.w(tag, "Connection to $host:$port timed out. If this is the first time, please accept the connection request on the host device.")
            return false
        }
        return true
    }

    suspend fun scanForDevices(port: Int, progressCallback: (String) -> Unit): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)

        val ipAddressString = linkProperties?.linkAddresses?.find { it.address is Inet4Address }?.address?.hostAddress

        if (ipAddressString != null) {
            val prefix = ipAddressString.substring(0, ipAddressString.lastIndexOf('.') + 1)
            progressCallback("Scanning subnet of $ipAddressString...\n")

            // Scan the last octet from 1 to 254
            for (i in 1..254) {
                val host = prefix + i
                if (host == ipAddressString) continue

                progressCallback("Scanning $host:$port...\n")
                if (isPortOpen(host, port, 50)) {
                    progressCallback("Found device at $host\n")
                    return host
                }
            }
        } else {
            progressCallback("Not connected to a Wi-Fi network.\n")
        }
        progressCallback("Scan finished. No devices found.\n")
        return null
    }

    private fun isPortOpen(ip: String, port: Int, timeout: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeout)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }


    private suspend fun keygen(): AdbKeyPair = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val privateCert = File(cacheDir, "adbkey")
        val pubCert = File(cacheDir, "adbkey.pub")

        if (!privateCert.exists() || !pubCert.exists()) {
            AdbKeyPair.generate(privateCert, pubCert)
        }
        AdbKeyPair.read(privateCert, pubCert)

    }

    fun bugreport(): Pair<Boolean, String> {
        Log.w(tag, "Starting bug report..")

        val downloadsPath = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val buggappDirectory = File(downloadsPath, "bugapp")
        if (!buggappDirectory.exists()) {
            Log.w(tag, "Creating bugapp folder in app's external files dir")
            buggappDirectory.mkdirs()
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
                return Pair(true, file.absolutePath)
            }
        } catch (e: Throwable) {
            Log.e(tag, "Exception in ${::bugreport.name}: ${e.message}")
            e.printStackTrace()
        }
        return Pair(false, "")
    }
}
