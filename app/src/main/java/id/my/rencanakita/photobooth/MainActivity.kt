package id.my.rencanakita.photobooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    companion object {
        const val HOST = "photo.rencanakita.my.id"
        const val START_URL = "https://photo.rencanakita.my.id/booth.php"
        const val DIAG_URL = "file:///android_asset/diag.html"
    }

    private lateinit var web: WebView
    private lateinit var usb: UsbCamera

    /** Jembatan JavaScript. Hanya dipakai oleh halaman dari situs sendiri dan halaman diagnosa. */
    inner class Bridge {
        @JavascriptInterface
        fun list(): String = usb.listJson()

        @JavascriptInterface
        fun request(id: Int) {
            runOnUiThread { usb.requestPermission(id) }
        }

        @JavascriptInterface
        fun info(id: Int, reqId: String) {
            Thread {
                val json = usb.info(id)
                runOnUiThread {
                    web.evaluateJavascript(
                        "window.onPBInfo && window.onPBInfo(" + org.json.JSONObject.quote(reqId) + "," + json + ")",
                        null,
                    )
                }
            }.start()
        }

        /** Jepret dengan Canon USB. Hasil dikirim ke window.onPBCapture(reqId, json); foto ada di /__pbcam/last.jpg. */
        @JavascriptInterface
        fun capture(reqId: String) {
            Thread {
                val json = usb.capture(java.io.File(cacheDir, "pbcam"))
                runOnUiThread {
                    web.evaluateJavascript(
                        "window.onPBCapture && window.onPBCapture(" + org.json.JSONObject.quote(reqId) + "," + json + ")",
                        null,
                    )
                }
            }.start()
        }

        @JavascriptInterface
        fun version(): String = "0.2.0"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        usb = UsbCamera(this) { notifyUsb() }

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#e9eef5"))
        setContentView(web)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = s.userAgentString + " PhotoboothApp/0.1"
        web.addJavascriptInterface(Bridge(), "PBCamera")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(view, request.url)

            @Deprecated("Untuk Android lama")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleUrl(view, Uri.parse(url))

            /** Sajikan foto kamera dari berkas lokal sebagai https://HOST/__pbcam/last.jpg (satu origin dengan booth). */
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                val u = request.url
                if (u.scheme == "https" && u.host == HOST && u.path == "/__pbcam/last.jpg") {
                    val f = java.io.File(cacheDir, "pbcam/last.jpg")
                    if (f.isFile) {
                        val r = android.webkit.WebResourceResponse("image/jpeg", null, java.io.FileInputStream(f))
                        r.responseHeaders = mapOf("Cache-Control" to "no-store")
                        return r
                    }
                    return android.webkit.WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError(view)
            }

            @Deprecated("Untuk Android lama")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                if (failingUrl == START_URL || failingUrl == null) showError(view)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val ok = request.origin.host == HOST
                runOnUiThread { if (ok) request.grant(request.resources) else request.deny() }
            }
        }

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }

        web.loadUrl(START_URL)
        handleIntent(intent)
    }

    private fun handleUrl(view: WebView, u: Uri): Boolean {
        if (u.scheme == "pbapp") {
            view.loadUrl(if (u.host == "diag") DIAG_URL else START_URL)
            return true
        }
        if (u.scheme == "file" || (u.scheme == "https" && u.host == HOST)) return false
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u))
        } catch (_: Exception) {}
        return true
    }

    private fun showError(view: WebView) {
        val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<meta http-equiv='refresh' content='5;url=" + START_URL + "'></head>" +
            "<body style='font-family:sans-serif;background:#e9eef5;color:#0f1420;text-align:center;padding:12vh 24px'>" +
            "<h2>Tidak bisa terhubung</h2><p>Periksa internet tablet. Mencoba lagi otomatis tiap 5 detik.</p>" +
            "<p><a href='pbapp://diag'>Diagnosa kamera USB</a></p></body></html>"
        view.loadDataWithBaseURL(START_URL, html, "text/html", "utf-8", null)
    }

    private fun notifyUsb() {
        runOnUiThread {
            web.evaluateJavascript("window.dispatchEvent(new Event('pb-usb'))", null)
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) notifyUsb()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        usb.start()
    }

    override fun onStop() {
        usb.stop()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
