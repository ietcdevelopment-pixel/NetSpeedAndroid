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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private lateinit var networkValue: TextView
    private lateinit var heroLabel: TextView
    private lateinit var heroValue: TextView
    private lateinit var heroUnit: TextView
    private lateinit var pingValue: TextView
    private lateinit var downloadValue: TextView
    private lateinit var uploadValue: TextView
    private lateinit var statusValue: TextView
    private lateinit var progress: ProgressBar
    private lateinit var speedWave: SpeedWaveView
    private lateinit var startButton: Button
    private lateinit var historyEmpty: TextView
    private lateinit var historyContainer: LinearLayout
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
        heroLabel = findViewById(R.id.hero_label)
        heroValue = findViewById(R.id.hero_value)
        heroUnit = findViewById(R.id.hero_unit)
        pingValue = findViewById(R.id.ping_value)
        downloadValue = findViewById(R.id.download_value)
        uploadValue = findViewById(R.id.upload_value)
        statusValue = findViewById(R.id.status_value)
        progress = findViewById(R.id.progress)
        speedWave = findViewById(R.id.speed_wave)
        startButton = findViewById(R.id.start_button)
        historyEmpty = findViewById(R.id.history_empty)
        historyContainer = findViewById(R.id.history_container)
        versionValue = findViewById(R.id.version_value)
        versionValue.text = getString(
            R.string.version_format,
            packageManager.getPackageInfo(packageName, 0).versionName,
        )
        browserUserAgent = WebSettings.getDefaultUserAgent(this)
        transferClient = WebViewSpeedTestClient(findViewById<WebView>(R.id.speed_web_view))

        startButton.setOnClickListener { startSpeedTest() }
        renderHistory(loadHistory())
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
            statusValue.setTextColor(getColor(R.color.error))
            return
        }

        setTesting(true)
        pingValue.text = getString(R.string.not_measured)
        downloadValue.text = getString(R.string.not_measured)
        uploadValue.text = getString(R.string.not_measured)
        heroLabel.text = getString(R.string.ping_label)
        heroValue.text = getString(R.string.not_measured)
        heroUnit.text = ""
        progress.progress = 0

        activeTest = executor.submit {
            val pingClient = SpeedTestClient(browserUserAgent)
            var stage = getString(R.string.testing_ping)
            try {
                postStatus(stage, 0)
                val ping = pingClient.measurePingMs()
                postUi {
                    pingValue.text = getString(R.string.ping_format, ping)
                    setHeroValue(getString(R.string.number_no_decimals, ping), R.string.ms_unit)
                    progress.progress = 10
                }

                stage = getString(R.string.testing_download)
                postStatus(stage, 10)
                postUi {
                    heroLabel.text = getString(R.string.download_label)
                    heroValue.text = getString(R.string.not_measured)
                    heroUnit.text = ""
                }
                val download = transferClient.measureDownloadMbps { done, total, current ->
                    postUi {
                        setHeroValue(getString(R.string.number_one_decimal, current), R.string.mbps_unit)
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
                postUi {
                    heroLabel.text = getString(R.string.upload_label)
                    heroValue.text = getString(R.string.not_measured)
                    heroUnit.text = ""
                }
                val upload = transferClient.measureUploadMbps { done, total, current ->
                    postUi {
                        setHeroValue(getString(R.string.number_one_decimal, current), R.string.mbps_unit)
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
                    heroLabel.text = getString(R.string.download_label)
                    setHeroValue(getString(R.string.number_one_decimal, download), R.string.mbps_unit)
                    progress.progress = 100
                    statusValue.text = getString(R.string.test_complete)
                    statusValue.setTextColor(getColor(R.color.success))
                    saveHistory(TestHistoryEntry(System.currentTimeMillis(), network, ping, download, upload))
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
                    statusValue.setTextColor(getColor(R.color.error))
                    setTesting(false)
                }
            }
        }
    }

    private fun postStatus(status: String, progressValue: Int) {
        postUi {
            statusValue.text = status
            statusValue.setTextColor(getColor(R.color.text_secondary))
            progress.progress = progressValue
        }
    }

    private fun setTesting(testing: Boolean) {
        speedWave.setRunning(testing)
        startButton.isEnabled = !testing
        startButton.text = getString(if (testing) R.string.testing else R.string.test_again)
        progress.visibility = if (testing) View.VISIBLE else View.INVISIBLE
    }

    private fun setHeroValue(value: String, unit: Int) {
        heroValue.text = value
        heroUnit.setText(unit)
    }

    private fun updateNetworkType() {
        networkValue.text = currentNetworkType()
    }

    private fun loadHistory(): List<TestHistoryEntry> = TestHistory.decode(
        getSharedPreferences(HISTORY_PREFERENCES, MODE_PRIVATE)
            .getString(HISTORY_KEY, "").orEmpty(),
    )

    private fun saveHistory(entry: TestHistoryEntry) {
        val entries = TestHistory.add(loadHistory(), entry)
        getSharedPreferences(HISTORY_PREFERENCES, MODE_PRIVATE).edit()
            .putString(HISTORY_KEY, TestHistory.encode(entries))
            .apply()
        renderHistory(entries)
    }

    private fun renderHistory(entries: List<TestHistoryEntry>) {
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        historyEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        historyContainer.removeAllViews()
        entries.forEach { entry ->
            val row = layoutInflater.inflate(R.layout.history_item, historyContainer, false)
            row.findViewById<TextView>(R.id.history_meta).text = getString(
                R.string.history_meta,
                dateFormat.format(Date(entry.timestampMillis)),
                entry.network,
            )
            row.findViewById<TextView>(R.id.history_ping).text = getString(R.string.history_ping, entry.pingMs)
            row.findViewById<TextView>(R.id.history_download).text =
                getString(R.string.history_download, entry.downloadMbps)
            row.findViewById<TextView>(R.id.history_upload).text =
                getString(R.string.history_upload, entry.uploadMbps)
            historyContainer.addView(row)
        }
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

    companion object {
        private const val HISTORY_PREFERENCES = "speed_test_history"
        private const val HISTORY_KEY = "successful_tests"
    }
}
