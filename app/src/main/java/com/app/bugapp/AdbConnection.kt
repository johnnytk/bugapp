package com.app.bugapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

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

    suspend fun scanForDevices(
        port: Int,
        timeout: Int,
        concurrency: Int,
        progressCallback: (Pair<Int, String>) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = connectivityManager.allNetworks.mapNotNull { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    network
                } else {
                    null
                }
            }
        }

        if (networks.isEmpty()) {
            progressCallback(Pair(0, "Not connected to a Wi-Fi network.\n"))
            return@withContext null
        }

        for (network in networks) {
            val linkProperties = connectivityManager.getLinkProperties(network)
            val ipAddressString = linkProperties?.linkAddresses?.find { it.address is Inet4Address }?.address?.hostAddress

            if (ipAddressString != null) {
                val prefix = ipAddressString.take(ipAddressString.lastIndexOf('.') + 1)
                progressCallback(Pair(0, "Scanning subnet of $ipAddressString with $concurrency threads...\n"))

                val totalDevices = 254
                val semaphore = Semaphore(concurrency)
                val progressCount = AtomicInteger(0)
                val resultChannel = Channel<String?>(Channel.CONFLATED)
                
                val jobs = (1..totalDevices).map { i ->
                    launch {
                        val host = prefix + i
                        if (host == ipAddressString) {
                            val current = progressCount.incrementAndGet()
                            progressCallback(Pair(current, "Skipping $host (self)"))
                            if (current == totalDevices) resultChannel.trySend(null)
                            return@launch
                        }

                        semaphore.withPermit {
                            val current = progressCount.incrementAndGet()
                            progressCallback(Pair(current, "Scanning $host:$port..."))
                            if (isPortOpen(host, port, timeout)) {
                                resultChannel.trySend(host)
                            } else if (current == totalDevices) {
                                resultChannel.trySend(null)
                            }
                        }
                    }
                }

                val foundHost = resultChannel.receive()
                jobs.forEach { it.cancel() }
                
                if (foundHost != null) {
                    progressCallback(Pair(totalDevices, "Found device at $foundHost\n"))
                    return@withContext foundHost
                }
            }
        }

        progressCallback(Pair(254, "Scan finished. No devices found.\n"))
        null
    }

    private fun isPortOpen(ip: String, port: Int, timeout: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeout)
            socket.close()
            true
        } catch (_: IOException) {
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
