package com.netspeedandroid.app

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private lateinit var networkValue: TextView
    private lateinit var pingValue: TextView
    private lateinit var downloadValue: TextView
    private lateinit var uploadValue: TextView
    private lateinit var statusValue: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startButton: Button
    private lateinit var versionValue: TextView
    private lateinit var browserUserAgent: String
    private lateinit var transferClient: WebViewSpeedTestClient

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var activeTest: Future<*>? = null
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        networkValue = findViewById(R.id.network_value)
        pingValue = findViewById(R.id.ping_value)
        downloadValue = findViewById(R.id.download_value)
        uploadValue = findViewById(R.id.upload_value)
        statusValue = findViewById(R.id.status_value)
        progress = findViewById(R.id.progress)
        startButton = findViewById(R.id.start_button)
        versionValue = findViewById(R.id.version_value)
        versionValue.text = getString(
            R.string.version_format,
            packageManager.getPackageInfo(packageName, 0).versionName,
        )
        browserUserAgent = WebSettings.getDefaultUserAgent(this)
        transferClient = WebViewSpeedTestClient(findViewById<WebView>(R.id.speed_web_view))

        startButton.setOnClickListener { startSpeedTest() }
        updateNetworkType()
    }

    override fun onResume() {
        super.onResume()
        updateNetworkType()
    }

    private fun startSpeedTest() {
        val network = currentNetworkType()
        networkValue.text = network
        if (network == getString(R.string.no_network)) {
            statusValue.text = getString(R.string.no_network_error)
            return
        }

        setTesting(true)
        pingValue.text = getString(R.string.not_measured)
        downloadValue.text = getString(R.string.not_measured)
        uploadValue.text = getString(R.string.not_measured)
        progress.progress = 0

        activeTest = executor.submit {
            val pingClient = SpeedTestClient(browserUserAgent)
            var stage = getString(R.string.testing_ping)
            try {
                postStatus(stage, 0)
                val ping = pingClient.measurePingMs()
                postUi {
                    pingValue.text = getString(R.string.ping_format, ping)
                    progress.progress = 10
                }

                stage = getString(R.string.testing_download)
                postStatus(stage, 10)
                val download = transferClient.measureDownloadMbps { done, total, current ->
                    postUi {
                        statusValue.text = getString(
                            R.string.download_progress,
                            done / 1_000_000,
                            total / 1_000_000,
                            current,
                        )
                        progress.progress = 10 + (done * 65 / total).toInt()
                    }
                }
                postUi {
                    downloadValue.text = getString(R.string.speed_format, download)
                    progress.progress = 75
                }

                stage = getString(R.string.testing_upload)
                postStatus(stage, 75)
                val upload = transferClient.measureUploadMbps { done, total, current ->
                    postUi {
                        statusValue.text = getString(
                            R.string.upload_progress,
                            done / 1_000_000,
                            total / 1_000_000,
                            current,
                        )
                        progress.progress = 75 + (done * 25 / total).toInt()
                    }
                }
                postUi {
                    uploadValue.text = getString(R.string.speed_format, upload)
                    progress.progress = 100
                    statusValue.text = getString(R.string.test_complete)
                    setTesting(false)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                val detail = when (error) {
                    is IOException -> error.message ?: getString(R.string.network_error)
                    else -> error.message ?: error.javaClass.simpleName
                }
                postUi {
                    statusValue.text = getString(R.string.test_failed, stage, detail)
                    setTesting(false)
                }
            }
        }
    }

    private fun postStatus(status: String, progressValue: Int) {
        postUi {
            statusValue.text = status
            progress.progress = progressValue
        }
    }

    private fun setTesting(testing: Boolean) {
        startButton.isEnabled = !testing
        startButton.text = getString(if (testing) R.string.testing else R.string.test_again)
        progress.visibility = if (testing) View.VISIBLE else View.INVISIBLE
    }

    private fun updateNetworkType() {
        networkValue.text = currentNetworkType()
    }

    private fun currentNetworkType(): String {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return getString(R.string.no_network)
        val capabilities = manager.getNetworkCapabilities(network) ?: return getString(R.string.no_network)
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> getString(R.string.vpn)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getString(R.string.wifi)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getString(R.string.mobile_data)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> getString(R.string.ethernet)
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> getString(R.string.other_network)
            else -> getString(R.string.no_network)
        }
    }

    private fun postUi(action: () -> Unit) {
        if (!destroyed) runOnUiThread {
            if (!destroyed) action()
        }
    }

    override fun onDestroy() {
        destroyed = true
        activeTest?.cancel(true)
        executor.shutdownNow()
        transferClient.destroy()
        super.onDestroy()
    }
}
