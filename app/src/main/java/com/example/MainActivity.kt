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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    private var isPageFinished = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        bridge.logEvent("PERMISSION", "Audio permission result: $granted")
        if (isPageFinished) {
            evaluateJs("window.onAudioPermissionResult && window.onAudioPermissionResult($granted)")
            if (granted) {
                evaluateJs("window.scanLocalPhonksFolder && window.scanLocalPhonksFolder(true)")
            }
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

    private val coverPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            handleCoverImagePicked(uri)
        }
    }

    private val coverGetContentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handleCoverImagePicked(uri)
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

        // Create Edge-To-Edge FrameLayout Container with safe system window insets
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF07090E.toInt())
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        // Clean up any stale partial cache directory so Chromium SimpleCache initializes cleanly
        try {
            val defaultCache = java.io.File(cacheDir, "WebView/Default/HTTP Cache")
            if (defaultCache.exists()) {
                val indexFile = java.io.File(defaultCache, "index")
                if (!indexFile.exists()) {
                    defaultCache.deleteRecursively()
                }
            }
            val crashpadDir = java.io.File(cacheDir, "WebView/Crashpad/attachments")
            if (!crashpadDir.exists()) crashpadDir.mkdirs()
        } catch (_: Exception) {}

        // Configure High-Performance WebView
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF07090E.toInt())

            // Prevent Mesa rendernode errors on virtualized containers/headless emulators lacking /dev/dri
            try {
                if (!java.io.File("/dev/dri").exists()) {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }
            } catch (_: Exception) {}

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
                    isPageFinished = true
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

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: android.webkit.JsResult?
                ): Boolean {
                    try {
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("XP Music Vault")
                            .setMessage(message ?: "Confirm action?")
                            .setPositiveButton("DELETE") { dialog: android.content.DialogInterface, _: Int ->
                                result?.confirm()
                                dialog.dismiss()
                            }
                            .setNegativeButton("CANCEL") { dialog: android.content.DialogInterface, _: Int ->
                                result?.cancel()
                                dialog.dismiss()
                            }
                            .setOnCancelListener { result?.cancel() }
                            .show()
                        return true
                    } catch (e: Exception) {
                        result?.cancel()
                        return false
                    }
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: android.webkit.JsResult?
                ): Boolean {
                    try {
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("XP Music Vault")
                            .setMessage(message ?: "")
                            .setPositiveButton("OK") { dialog: android.content.DialogInterface, _: Int ->
                                result?.confirm()
                                dialog.dismiss()
                            }
                            .setOnCancelListener { result?.confirm() }
                            .show()
                        return true
                    } catch (e: Exception) {
                        result?.confirm()
                        return false
                    }
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

    fun launchCoverPicker() {
        try {
            coverPickerLauncher.launch(
                PickVisualMediaRequest(PickVisualMedia.ImageOnly)
            )
        } catch (e: Exception) {
            try {
                coverGetContentLauncher.launch("image/*")
            } catch (e2: Exception) {
                bridge.logEvent("COVER", "Image picker launch failed: ${e.message}")
            }
        }
    }

    private fun handleCoverImagePicked(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val rawBytes = inputStream.readBytes()
                val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                if (bmp != null) {
                    val maxDim = maxOf(bmp.width, bmp.height)
                    val targetBmp = if (maxDim > 512) {
                        val scale = 512f / maxDim
                        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                    } else {
                        bmp
                    }
                    val bos = ByteArrayOutputStream()
                    targetBmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
                    if (targetBmp != bmp) targetBmp.recycle()
                    bmp.recycle()
                    val jpegBytes = bos.toByteArray()
                    val b64 = "data:image/jpeg;base64," + Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                    bridge.logEvent("COVER", "Cover picked & encoded (${jpegBytes.size} bytes)")
                    runOnUiThread {
                        evaluateJs("window.onCoverImagePicked && window.onCoverImagePicked('$b64')")
                    }
                }
            }
        } catch (e: Exception) {
            bridge.logEvent("ERROR", "handleCoverImagePicked failed: ${e.message}")
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

    fun updateServiceMetadata(title: String, artist: String, isPlaying: Boolean, durationMs: Long, positionMs: Long, artUrl: String = "") {
        audioVaultService?.updateServiceState(title, artist, isPlaying, durationMs, positionMs, artUrl)
    }

    fun stopPlaybackService() {
        try {
            audioVaultService?.apply {
                pausePlayback()
                stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            }
        } catch (_: Exception) {}
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {}
            isServiceBound = false
        }
        try {
            stopService(Intent(this, AudioVaultService::class.java))
        } catch (_: Exception) {}
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

    override fun onPause() {
        super.onPause()
        evaluateJs("window.onNativeVisibilityChanged && window.onNativeVisibilityChanged(false)")
    }

    override fun onResume() {
        super.onResume()
        evaluateJs("window.onNativeVisibilityChanged && window.onNativeVisibilityChanged(true)")
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
