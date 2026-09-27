package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var streamInterceptor: StreamInterceptor
    private lateinit var bridge: AndroidBridge

    private var audioVaultService: AudioVaultService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioVaultService.LocalBinder
            audioVaultService = binder.getService()
            isServiceBound = true
            bridge.logEvent("SERVICE", "AudioVaultService bound successfully")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioVaultService = null
            isServiceBound = false
            bridge.logEvent("SERVICE", "AudioVaultService disconnected")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        bridge.logEvent("PERMISSION", "Audio permission result: $granted")
        evaluateJs("window.onAudioPermissionResult && window.onAudioPermissionResult($granted)")
        if (granted) {
            evaluateJs("window.scanLocalPhonksFolder && window.scanLocalPhonksFolder()")
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val contentUri = uri.toString()
            val streamUrl = "https://appassets.androidplatform.net/local-audio/${Uri.encode(contentUri)}"
            val displayName = resolveFileName(uri)

            val specs = try {
                JSONObject(bridge.getTrackSpecs(contentUri))
            } catch (_: Exception) {
                JSONObject()
            }
            val album = specs.optString("album", "XP Phonk Vault")
            val bitDepth = specs.optInt("bitDepth", 16)

            val payload = JSONObject().apply {
                put("uri", contentUri)
                put("streamUrl", streamUrl)
                put("name", displayName)
                put("album", album)
                put("bitDepth", bitDepth)
            }.toString()

            bridge.logEvent("PICKER", "File picked: $displayName")
            evaluateJs("window.onAudioFileSelected && window.onAudioFileSelected($payload)")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Initialize Bridge, Services & Interceptors FIRST
        bridge = AndroidBridge(this)
        streamInterceptor = StreamInterceptor(this)
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // Bind & Start Foreground Playback Service safely
        try {
            val serviceIntent = Intent(this, AudioVaultService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            bridge.logEvent("SERVICE", "Service launch exception: ${e.message}")
        }

        AudioVaultService.onActionCallback = { action ->
            runOnUiThread {
                bridge.logEvent("MEDIA_ACTION", "IPC Action received from notification/headset: $action")
                evaluateJs("window.onNativeMediaAction && window.onNativeMediaAction('$action')")
            }
        }

        // Create Edge-To-Edge FrameLayout Container
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF07090E.toInt())
        }

        // Ensure WebView Cache, Code Cache, and Crashpad directories exist so Chromium never encounters missing directories during index scan
        try {
            val codeCacheDir = java.io.File(cacheDir, "WebView/Default/HTTP Cache/Code Cache")
            val wasmDir = java.io.File(codeCacheDir, "wasm")
            val jsDir = java.io.File(codeCacheDir, "js")
            val crashpadDir = java.io.File(cacheDir, "WebView/Crashpad/attachments")
            if (!wasmDir.exists()) wasmDir.mkdirs()
            if (!jsDir.exists()) jsDir.mkdirs()
            if (!crashpadDir.exists()) crashpadDir.mkdirs()
        } catch (_: Exception) {}

        // Configure High-Performance WebView
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF07090E.toInt())
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                loadsImagesAutomatically = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
            }

            addJavascriptInterface(bridge, "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request == null) return null
                    val intercepted = streamInterceptor.intercept(request)
                    if (intercepted != null) return intercepted
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    bridge.logEvent("WEBVIEW", "Frontend core loaded: $url")
                    // Notify web app that WebView is completely rendered and safe for heavy operations
                    evaluateJs("window.onWebViewFullyReady && window.onWebViewFullyReady()")
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    bridge.logEvent("RENDERER", "Render process gone: didCrash=${detail?.didCrash()}")
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        bridge.logEvent("JS_ERR", consoleMessage.message())
                    }
                    return true
                }
            }
        }

        rootLayout.addView(webView)
        setContentView(rootLayout)

        // Request audio permissions on launch so songs can be accessed
        if (!checkAudioPermission()) {
            requestAudioPermission()
        }

        // Load entrypoint
        webView.loadUrl("https://appassets.androidplatform.net/assets/vault/index.html")
    }

    fun launchFilePicker() {
        try {
            filePickerLauncher.launch(
                arrayOf(
                    "audio/*",
                    "audio/wav",
                    "audio/x-wav",
                    "audio/flac",
                    "audio/x-flac",
                    "audio/aiff",
                    "audio/x-aiff",
                    "audio/alac",
                    "audio/mp4",
                    "audio/ogg",
                    "audio/opus",
                    "application/ogg",
                    "application/x-flac",
                    "application/octet-stream"
                )
            )
        } catch (_: Exception) {
            filePickerLauncher.launch(arrayOf("*/*"))
        }
    }

    fun checkAudioPermission(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Exception) {
            bridge.logEvent("PERMISSION", "Check permission error: ${e.message}")
            false
        }
    }

    fun requestAudioPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_MEDIA_AUDIO,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                )
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.RECORD_AUDIO
                    )
                )
            }
        } catch (e: Exception) {
            bridge.logEvent("PERMISSION", "Request permission error: ${e.message}")
        }
    }

    fun getAudioService(): AudioVaultService? = audioVaultService

    fun updateServiceMetadata(title: String, artist: String, isPlaying: Boolean, durationMs: Long, positionMs: Long) {
        audioVaultService?.updateServiceState(title, artist, isPlaying, durationMs, positionMs)
    }

    fun stopPlaybackService() {
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {}
            isServiceBound = false
        }
        stopService(Intent(this, AudioVaultService::class.java))
    }

    fun setSystemFullscreen(fullscreen: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (fullscreen) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            if (fullscreen) {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
            } else {
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    fun evaluateJs(code: String) {
        webView.post {
            webView.evaluateJavascript(code, null)
        }
    }

    private fun resolveFileName(uri: Uri): String {
        var name = "Selected Track"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: "Selected Track"
            }
        }
        return name
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        evaluateJs("if (window.handleBackPress && window.handleBackPress()) { /* Handled */ } else { AndroidBridge.logEvent('NAV', 'Exit via Back'); window.location.href = 'about:blank'; }")
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {}
            isServiceBound = false
        }
    }
}

// Preserve Greeting composable so Robolectric GreetingScreenshotTest continues to pass cleanly
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
