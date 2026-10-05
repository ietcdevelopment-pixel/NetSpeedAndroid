package com.netspeedandroid.app

import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class WebViewSpeedTestClient(private val webView: WebView) {
    private val ready = CountDownLatch(1)
    private val nextRequestId = AtomicLong()
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
    ): Double = run("runDownload", onProgress)

    fun measureUploadMbps(
        onProgress: ((bytesDone: Long, totalBytes: Long, currentMbps: Double) -> Unit)? = null,
    ): Double = run("runUpload", onProgress)

    fun destroy() {
        request?.fail("Test cancelled")
        request = null
        cancelBrowserTest()
        webView.removeJavascriptInterface(BRIDGE_NAME)
        webView.stopLoading()
        webView.destroy()
    }

    private fun run(
        functionName: String,
        onProgress: ((Long, Long, Double) -> Unit)?,
    ): Double {
        if (!ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException("Browser engine did not start")
        }
        val current = Request(nextRequestId.incrementAndGet(), onProgress)
        check(request == null) { "A browser speed test is already running" }
        request = current
        webView.post {
            webView.evaluateJavascript("cancelActive(); $functionName(${current.id});", null)
        }
        try {
            if (!current.done.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                cancelBrowserTest()
                throw IOException("Browser speed test timed out")
            }
            current.error?.let { throw IOException(it) }
            return current.result ?: throw IOException("Browser speed test returned no result")
        } finally {
            if (request === current) request = null
        }
    }

    private fun cancelBrowserTest() {
        val cancel = { webView.evaluateJavascript("cancelActive();", null) }
        if (Looper.myLooper() == Looper.getMainLooper()) cancel() else webView.post(cancel)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun progress(requestId: Double, bytesDone: Double, totalBytes: Double, mbps: Double) {
            request?.takeIf { it.id == requestId.toLong() }
                ?.onProgress?.invoke(bytesDone.toLong(), totalBytes.toLong(), mbps)
        }

        @JavascriptInterface
        fun sample(
            requestId: Double,
            bytes: Double,
            durationMs: Double,
            bytesDone: Double,
            totalBytes: Double,
        ) {
            request?.takeIf { it.id == requestId.toLong() }
                ?.addSample(bytes.toLong(), durationMs, bytesDone.toLong(), totalBytes.toLong())
        }

        @JavascriptInterface
        fun complete(requestId: Double) {
            request?.takeIf { it.id == requestId.toLong() }?.complete()
        }

        @JavascriptInterface
        fun fail(requestId: Double, message: String) {
            request?.takeIf { it.id == requestId.toLong() }?.fail(message)
        }
    }

    private class Request(
        val id: Long,
        val onProgress: ((Long, Long, Double) -> Unit)?,
    ) {
        val done = CountDownLatch(1)
        private val speeds = ArrayList<Double>(3)
        @Volatile var result: Double? = null
        @Volatile var error: String? = null

        fun addSample(
            bytes: Long,
            durationMs: Double,
            bytesDone: Long,
            totalBytes: Long,
        ) {
            if (bytes <= 0 || !durationMs.isFinite() || durationMs < MIN_SAMPLE_DURATION_MS) {
                fail("Browser engine returned an invalid timing sample")
                return
            }
            val speed = megabitsPerSecond(bytes, (durationMs * 1_000_000.0).toLong())
            speeds += speed
            onProgress?.invoke(bytesDone, totalBytes, speed)
        }

        fun complete() {
            if (speeds.isEmpty()) {
                fail("Browser speed test returned no valid samples")
                return
            }
            result = median(speeds)
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
        internal val DOWNLOAD_STAGES = longArrayOf(2_000_000L, 16_000_000L, 16_000_000L, 16_000_000L)
        internal val UPLOAD_STAGES = longArrayOf(1_000_000L, 3_000_000L, 3_000_000L, 3_000_000L)
        private const val BASE_URL = "https://speed.cloudflare.com/"
        private const val BRIDGE_NAME = "AndroidBridge"
        private const val READY_TIMEOUT_SECONDS = 15L
        private const val TEST_TIMEOUT_SECONDS = 120L
        private const val MIN_SAMPLE_DURATION_MS = 10.0

        internal val PAGE = """
            <!doctype html><meta charset="utf-8"><script>
            const downloadStages = [2000000, 16000000, 16000000, 16000000];
            const uploadStages = [1000000, 3000000, 3000000, 3000000];
            const minimumSampleMs = 10;
            let activeController = null;

            function cancelActive() {
              if (activeController) activeController.abort();
              activeController = null;
            }

            function serverTimeMs(response) {
              const header = response.headers.get('server-timing') || '';
              const request = header.match(/cfReq(?:uest)?Dur(?:ation)?;\s*dur=([0-9.]+)/i);
              if (request) return Number(request[1]);
              return [...header.matchAll(/cfSpeed[a-z]*;\s*dur=([0-9.]+)/ig)]
                .reduce((sum, match) => sum + Number(match[1]), 0);
            }

            function fallbackDownloadMs(elapsed, response) {
              const corrected = elapsed - serverTimeMs(response);
              return corrected >= 1 ? corrected : elapsed;
            }

            function latestTiming(url) {
              return performance.getEntriesByName(new URL(url, document.baseURI).href).slice(-1)[0];
            }

            function downloadDurationMs(url, response, fallback) {
              const timing = latestTiming(url);
              if (!timing) return fallbackDownloadMs(fallback, response);
              const ttfb = timing.responseStart - timing.requestStart;
              const payload = timing.responseEnd - timing.responseStart;
              const duration = ttfb - serverTimeMs(response) + payload;
              return duration >= 1 ? duration : fallbackDownloadMs(fallback, response);
            }

            function uploadDurationMs(url, fallback) {
              const timing = latestTiming(url);
              if (!timing) return fallback;
              const duration = timing.responseStart - timing.requestStart;
              return duration >= 1 ? duration : fallback;
            }

            function requireValidDuration(duration) {
              if (!Number.isFinite(duration) || duration < minimumSampleMs) {
                throw new Error('Browser engine returned an invalid timing sample');
              }
              return duration;
            }

            function startController() {
              cancelActive();
              if (!window.AbortController) throw new Error('Android System WebView is too old');
              activeController = new AbortController();
              performance.clearResourceTimings();
              return activeController;
            }

            async function runDownload(testId) {
              const controller = startController();
              try {
                let total = 0;
                let lastProgress = 0;
                for (let index = 0; index < downloadStages.length; index++) {
                  const bytes = downloadStages[index];
                  const url = '/__down?bytes=' + bytes;
                  const started = performance.now();
                  const response = await fetch(url, {cache: 'no-store', signal: controller.signal});
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
                      const mbps = received * 8 / (Math.max(now - started, 1) / 1000) / 1000000;
                      AndroidBridge.progress(testId, total, 50000000, mbps);
                      lastProgress = now;
                    }
                  }
                  if (!received) throw new Error('Download returned no data');
                  const duration = requireValidDuration(
                    downloadDurationMs(url, response, performance.now() - started));
                  const mbps = received * 8 / (duration / 1000) / 1000000;
                  if (index > 0) {
                    AndroidBridge.sample(testId, received, duration, total, 50000000);
                  } else {
                    AndroidBridge.progress(testId, total, 50000000, mbps);
                  }
                }
                AndroidBridge.complete(testId);
              } catch (error) {
                AndroidBridge.fail(testId, error.name === 'AbortError' ? 'Test cancelled' :
                  (error.message || String(error)));
              } finally {
                if (activeController === controller) activeController = null;
              }
            }

            async function runUpload(testId) {
              const controller = startController();
              try {
                let total = 0;
                for (let index = 0; index < uploadStages.length; index++) {
                  const bytes = uploadStages[index];
                  const url = '/__up?bytes=' + bytes;
                  const started = performance.now();
                  const response = await fetch(url, {
                    method: 'POST',
                    cache: 'no-store',
                    headers: {'Content-Type': 'application/octet-stream'},
                    body: new Uint8Array(bytes),
                    signal: controller.signal
                  });
                  if (!response.ok) throw new Error('Server returned HTTP ' + response.status);
                  const duration = requireValidDuration(
                    uploadDurationMs(url, performance.now() - started));
                  total += bytes;
                  const mbps = bytes * 8 / (duration / 1000) / 1000000;
                  if (index > 0) {
                    AndroidBridge.sample(testId, bytes, duration, total, 10000000);
                  } else {
                    AndroidBridge.progress(testId, total, 10000000, mbps);
                  }
                }
                AndroidBridge.complete(testId);
              } catch (error) {
                AndroidBridge.fail(testId, error.name === 'AbortError' ? 'Test cancelled' :
                  (error.message || String(error)));
              } finally {
                if (activeController === controller) activeController = null;
              }
            }
            </script>
        """.trimIndent()
    }
}
