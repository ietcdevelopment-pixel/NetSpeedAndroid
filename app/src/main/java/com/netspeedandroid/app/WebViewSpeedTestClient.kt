package com.netspeedandroid.app

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class WebViewSpeedTestClient(private val webView: WebView) {
    private val ready = CountDownLatch(1)
    @Volatile private var request: Request? = null

    init {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = false
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                ready.countDown()
            }
        }
        webView.loadDataWithBaseURL(BASE_URL, PAGE, "text/html", "UTF-8", null)
    }

    fun measureDownloadMbps(
        onProgress: ((bytesDone: Long, totalBytes: Long, currentMbps: Double) -> Unit)? = null,
    ): Double = run("runDownload()", onProgress)

    fun measureUploadMbps(
        onProgress: ((bytesDone: Long, totalBytes: Long, currentMbps: Double) -> Unit)? = null,
    ): Double = run("runUpload()", onProgress)

    fun destroy() {
        request?.fail("Test cancelled")
        request = null
        webView.removeJavascriptInterface(BRIDGE_NAME)
        webView.stopLoading()
        webView.destroy()
    }

    private fun run(
        script: String,
        onProgress: ((Long, Long, Double) -> Unit)?,
    ): Double {
        if (!ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException("Browser engine did not start")
        }
        val current = Request(onProgress)
        check(request == null) { "A browser speed test is already running" }
        request = current
        webView.post { webView.evaluateJavascript(script, null) }
        try {
            if (!current.done.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException("Browser speed test timed out")
            }
            current.error?.let { throw IOException(it) }
            return current.result ?: throw IOException("Browser speed test returned no result")
        } finally {
            if (request === current) request = null
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun progress(bytesDone: Double, totalBytes: Double, mbps: Double) {
            request?.onProgress?.invoke(bytesDone.toLong(), totalBytes.toLong(), mbps)
        }

        @JavascriptInterface
        fun complete(mbps: Double) {
            request?.complete(mbps)
        }

        @JavascriptInterface
        fun fail(message: String) {
            request?.fail(message)
        }
    }

    private class Request(val onProgress: ((Long, Long, Double) -> Unit)?) {
        val done = CountDownLatch(1)
        @Volatile var result: Double? = null
        @Volatile var error: String? = null

        fun complete(value: Double) {
            result = value
            done.countDown()
        }

        fun fail(message: String) {
            error = message
            done.countDown()
        }
    }

    companion object {
        const val DOWNLOAD_BYTES = 50_000_000L
        const val UPLOAD_BYTES = 10_000_000L
        internal val DOWNLOAD_STAGES = longArrayOf(1_000_000L, 9_000_000L, 15_000_000L, 25_000_000L)
        internal val UPLOAD_STAGES = longArrayOf(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L)
        private const val BASE_URL = "https://speed.cloudflare.com/"
        private const val BRIDGE_NAME = "AndroidBridge"
        private const val READY_TIMEOUT_SECONDS = 15L
        private const val TEST_TIMEOUT_SECONDS = 120L

        private val PAGE = """
            <!doctype html><meta charset="utf-8"><script>
            const downloadStages = [1000000, 9000000, 15000000, 25000000];
            const uploadStages = [1000000, 2000000, 3000000, 4000000];

            function median(values) {
              const sorted = [...values].sort((a, b) => a - b);
              return sorted[Math.floor(sorted.length / 2)];
            }

            function serverTimeMs(response) {
              const header = response.headers.get('server-timing') || '';
              const request = header.match(/cfReq(?:uest)?Dur(?:ation)?;\s*dur=([0-9.]+)/i);
              if (request) return Number(request[1]);
              return [...header.matchAll(/cfSpeed[a-z]*;\s*dur=([0-9.]+)/ig)]
                .reduce((sum, match) => sum + Number(match[1]), 0);
            }

            function correctedMs(elapsed, response) {
              const corrected = elapsed - serverTimeMs(response);
              return corrected >= 1 ? corrected : elapsed;
            }

            async function runDownload() {
              try {
                const speeds = [];
                let total = 0;
                let lastProgress = 0;
                for (let index = 0; index < downloadStages.length; index++) {
                  const bytes = downloadStages[index];
                  const started = performance.now();
                  const response = await fetch('/__down?bytes=' + bytes, {cache: 'no-store'});
                  if (!response.ok) throw new Error('Server returned HTTP ' + response.status);
                  const reader = response.body.getReader();
                  let received = 0;
                  while (true) {
                    const part = await reader.read();
                    if (part.done) break;
                    received += part.value.byteLength;
                    total += part.value.byteLength;
                    const now = performance.now();
                    if (now - lastProgress >= 250) {
                      const mbps = received * 8 / (correctedMs(now - started, response) / 1000) / 1000000;
                      AndroidBridge.progress(total, 50000000, mbps);
                      lastProgress = now;
                    }
                  }
                  if (!received) throw new Error('Download returned no data');
                  const mbps = received * 8 / (correctedMs(performance.now() - started, response) / 1000) / 1000000;
                  if (index > 0) speeds.push(mbps);
                  AndroidBridge.progress(total, 50000000, mbps);
                }
                AndroidBridge.complete(median(speeds));
              } catch (error) {
                AndroidBridge.fail(error.message || String(error));
              }
            }

            async function runUpload() {
              try {
                const speeds = [];
                let total = 0;
                for (let index = 0; index < uploadStages.length; index++) {
                  const bytes = uploadStages[index];
                  const started = performance.now();
                  const response = await fetch('/__up', {
                    method: 'POST',
                    cache: 'no-store',
                    headers: {'Content-Type': 'application/octet-stream'},
                    body: new Uint8Array(bytes)
                  });
                  const elapsed = correctedMs(performance.now() - started, response);
                  if (!response.ok) throw new Error('Server returned HTTP ' + response.status);
                  total += bytes;
                  const mbps = bytes * 8 / (elapsed / 1000) / 1000000;
                  if (index > 0) speeds.push(mbps);
                  AndroidBridge.progress(total, 10000000, mbps);
                }
                AndroidBridge.complete(median(speeds));
              } catch (error) {
                AndroidBridge.fail(error.message || String(error));
              }
            }
            </script>
        """.trimIndent()
    }
}
