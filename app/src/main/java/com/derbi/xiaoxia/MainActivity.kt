package com.derbi.xiaoxia

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.derbi.xiaoxia.network.ApiService
import com.derbi.xiaoxia.repository.SessionManager
import com.derbi.xiaoxia.repository.impl.SessionManagerImpl
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sessionManager: SessionManager
    private lateinit var apiService: ApiService
    private var shouldClearHistory = true // 标记是否需要清除历史记录
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    // 文件上传相关
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // 语音录制相关
    private var isRecording = false
    private var voiceRecognitionCallback: ((String) -> Unit)? = null

    // 流式请求管理
    private val activeRequests = ConcurrentHashMap<String, okhttp3.Call>()

    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "权限已授予")
        } else {
            Toast.makeText(this, "权限被拒绝，某些功能可能无法使用", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        const val CHAT_URL = "file:///android_asset/index.html"
        private const val REQUEST_CODE_FILE_PICKER = 1001
        private const val REQUEST_CODE_AUDIO_PERMISSION = 1002
        private const val REQUEST_CODE_STORAGE_PERMISSION = 1003

        // MIME类型映射
        private val mimeTypes = mapOf(
            ".html" to "text/html",
            ".js" to "application/javascript",
            ".css" to "text/css",
            ".json" to "application/json",
            ".png" to "image/png",
            ".jpg" to "image/jpeg",
            ".jpeg" to "image/jpeg",
            ".gif" to "image/gif",
            ".svg" to "image/svg+xml",
            ".woff" to "font/woff",
            ".woff2" to "font/woff2",
            ".ttf" to "font/ttf",
            ".eot" to "application/vnd.ms-fontobject",
            ".mp3" to "audio/mpeg",
            ".mp4" to "video/mp4",
            ".webm" to "video/webm",
            ".pdf" to "application/pdf",
            ".txt" to "text/plain",
            ".md" to "text/markdown"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()

        // 设置WebView为全屏
        setContentView(R.layout.activity_main)

        // 初始化服务
        initServices()

        // 初始化WebView
        initWebView()

        // 设置返回键处理
        setupBackPressedHandler()

        // 加载聊天页面
        loadChatPage()

        // 请求必要权限
        requestNecessaryPermissions()
    }

    /**
     * 请求必要权限
     */
    private fun requestNecessaryPermissions() {
        // 检查并请求网络状态权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_NETWORK_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.ACCESS_NETWORK_STATE)
            }
        }
    }

    private fun initServices() {
        val gson = GsonBuilder()
            .setLenient()
            .create()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://derbi.net.cn")
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        apiService = retrofit.create(ApiService::class.java)
        sessionManager = SessionManagerImpl(applicationContext)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webView = findViewById(R.id.webview)

        // 基础设置
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            textZoom = 100

            // 启用现代Web功能
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            // 设置UserAgent
            val defaultUserAgent = userAgentString
            userAgentString = "$defaultUserAgent XiaoxiaApp/1.0 Android"
        }

        // 清除历史记录（只清一次）
        if (shouldClearHistory) {
            webView.clearHistory()
            webView.clearCache(true)
            shouldClearHistory = false
        }

        // 设置WebViewClient，拦截请求
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                request?.let {
                    val url = it.url.toString()
                    Log.d(TAG, "拦截请求: $url")

                    // 拦截以 /xiaoXia 开头的相对路径请求
                    if (url.startsWith("file:///android_asset/") && url.contains("/xiaoXia/")) {
                        // 将本地路径转换为服务器API路径
                        val apiPath = url.substringAfter("/xiaoXia/")
                        val serverUrl = "https://derbi.net.cn/xiaoXia/$apiPath"
                        return interceptApiRequest(serverUrl)
                    }

                    // 拦截特定API请求，添加认证头
                    if (url.contains("derbi.net.cn") && url.contains("/api/")) {
                        return interceptApiRequest(url)
                    }

                    // 拦截SSE请求（Server-Sent Events）
                    if (url.contains("derbi.net.cn") && url.contains("/stream")) {
                        return interceptSSERequest(url)
                    }

                    // 拦截本地文件请求
                    if (url.startsWith("file:///android_asset/")) {
                        return handleAssetRequest(url)
                    }

                    // 处理其他资源请求
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        return handleExternalResourceRequest(url)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                request?.let {
                    val url = it.url.toString()
                    Log.d(TAG, "处理URL加载: $url")

                    // 处理特殊协议
                    if (url.startsWith("intent://")) {
                        try {
                            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                            startActivity(intent)
                            return true
                        } catch (e: Exception) {
                            Log.e(TAG, "解析intent失败", e)
                        }
                    }

                    // 处理外部链接
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        if (url.contains("derbi.net.cn")) {
                            // 内部链接，在WebView中加载
                            return false
                        } else {
                            // 外部链接，用浏览器打开
                            openExternalBrowser(url)
                            return true
                        }
                    }

                    // 处理mailto、tel等协议
                    if (url.startsWith("mailto:") || url.startsWith("tel:") ||
                        url.startsWith("sms:") || url.startsWith("geo:")) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(intent)
                            return true
                        } catch (e: Exception) {
                            Log.e(TAG, "打开系统应用失败", e)
                        }
                    }
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "页面开始加载: $url")

                // 显示加载指示器
                // 可以根据需要添加加载动画
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "页面加载完成: $url")

                // 页面加载完成后，注入初始化数据
                injectAppConfig()

                // 隐藏加载指示器
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "页面加载错误: ${error?.description}, code: ${error?.errorCode}")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (error?.errorCode == ERROR_HOST_LOOKUP || error?.errorCode == ERROR_CONNECT) {
                        // 网络错误，可以显示离线页面
                        showOfflinePage()
                    }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                Log.e(TAG, "HTTP错误: ${errorResponse?.statusCode}, ${errorResponse?.reasonPhrase}")
            }
        }

        // 设置ChromeClient，处理文件上传等
        webView.webChromeClient = object : WebChromeClient() {

            // 处理文件上传
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback = filePathCallback

                try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }

                    // 设置多文件选择
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

                    // 设置可选择的文件类型
                    if (fileChooserParams?.acceptTypes != null) {
                        intent.type = if (fileChooserParams.acceptTypes.size > 1) {
                            fileChooserParams.acceptTypes.joinToString(", ")
                        } else {
                            fileChooserParams.acceptTypes.firstOrNull() ?: "*/*"
                        }
                    }

                    startActivityForResult(intent, REQUEST_CODE_FILE_PICKER)
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "打开文件选择器失败", e)
                    return false
                }
            }

            // 处理权限请求
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    request?.let {
                        val resources = it.resources
                        val grantedResources = mutableListOf<String>()

                        // 检查并授权权限
                        for (resource in resources) {
                            when (resource) {
                                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                    if (checkAudioPermission()) {
                                        grantedResources.add(resource)
                                    }
                                }
                                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                    if (checkCameraPermission()) {
                                        grantedResources.add(resource)
                                    }
                                }
                                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> {
                                    grantedResources.add(resource)
                                }
                                else -> {
                                    // 默认拒绝其他权限
                                }
                            }
                        }

                        if (grantedResources.isNotEmpty()) {
                            it.grant(grantedResources.toTypedArray())
                        } else {
                            it.deny()
                        }
                    }
                }
            }

            // 处理控制台消息
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    when (it.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e("WebView", "${it.sourceId()}:${it.lineNumber()} ${it.message()}")
                        ConsoleMessage.MessageLevel.WARNING -> Log.w("WebView", "${it.sourceId()}:${it.lineNumber()} ${it.message()}")
                        ConsoleMessage.MessageLevel.LOG -> Log.i("WebView", "${it.sourceId()}:${it.lineNumber()} ${it.message()}")
                        else -> Log.d("WebView", "${it.sourceId()}:${it.lineNumber()} ${it.message()}")
                    }
                }
                return true
            }

            // 处理进度变化
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // 可以在这里更新加载进度条
                Log.d(TAG, "页面加载进度: $newProgress%")
            }

            // 处理网页标题
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                title?.let {
                    // 可以在这里更新Activity标题
                    Log.d(TAG, "页面标题: $it")
                }
            }
        }

        // 设置JavaScript接口
        webView.addJavascriptInterface(EnhancedWebAppInterface(), "AndroidInterface")

        // 启用调试（仅开发环境）
        try {
            val isDebug = applicationContext.applicationInfo != null &&
                    (applicationContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

            WebView.setWebContentsDebuggingEnabled(isDebug)
        } catch (e: Exception) {
            WebView.setWebContentsDebuggingEnabled(false)
        }

        // 设置长按监听
        webView.setOnLongClickListener { view ->
            // 可以在这里处理长按事件，比如显示上下文菜单
            true
        }
    }

    /**
     * 设置返回键处理（兼容新API）
     */
    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // 如果WebView不能后退，则让系统处理
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * 拦截API请求，添加认证头
     */
    private fun interceptApiRequest(url: String): WebResourceResponse? {
        return try {
            val sessionId = sessionManager.getSessionId()
            val requestId = UUID.randomUUID().toString()

            val client = okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val requestBuilder = original.newBuilder()
                        .header("Content-Type", "application/json")
                        .header("X-Request-ID", requestId)
                        .header("X-Platform", "android")
                        .header("X-App-Version", "1.0.0")

                    // 添加sessionId头
                    if (sessionId.isNotEmpty()) {
                        requestBuilder.header("X-Session-Id", sessionId)
                    }

                    // 添加设备信息
                    requestBuilder.header("X-Device-Model", Build.MODEL)
                    requestBuilder.header("X-Android-Version", Build.VERSION.RELEASE)

                    chain.proceed(requestBuilder.build())
                }
                .build()

            val request = okhttp3.Request.Builder()
                .url(url)
                .build()

            val call = client.newCall(request)
            activeRequests[requestId] = call

            val response = call.execute()

            // 从响应头获取sessionId并保存
            val newSessionId = response.header("X-Session-Id")
            if (!newSessionId.isNullOrEmpty() && newSessionId != sessionId) {
                coroutineScope.launch {
                    sessionManager.saveSessionId(newSessionId)
                    // 更新Web中的sessionId
                    updateWebSessionId(newSessionId)
                }
            }

            // 从响应头获取其他信息
            val contentType = response.header("Content-Type", "application/json")
            val contentEncoding = response.header("Content-Encoding", "utf-8")

            val responseBody: ResponseBody? = response.body()
            WebResourceResponse(
                contentType ?: "application/json",
                contentEncoding ?: "utf-8",
                response.code(),
                response.message(),
                response.headers().toMultimap() as Map<String?, String?>?,
                responseBody?.byteStream()
            )
        } catch (e: Exception) {
            Log.e(TAG, "拦截API请求失败", e)
            val errorJson = """{"error": "请求失败: ${e.message}", "code": 500}"""
            val inputStream = ByteArrayInputStream(errorJson.toByteArray())
            WebResourceResponse("application/json", "utf-8", 500, "Internal Error", emptyMap(), inputStream)
        }
    }

    /**
     * 拦截SSE请求
     */
    private fun interceptSSERequest(url: String): WebResourceResponse? {
        return try {
            val sessionId = sessionManager.getSessionId()

            val client = okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val requestBuilder = original.newBuilder()
                        .header("Accept", "text/event-stream")
                        .header("Cache-Control", "no-cache")
                        .header("X-Platform", "android")

                    if (sessionId.isNotEmpty()) {
                        requestBuilder.header("X-Session-Id", sessionId)
                    }

                    chain.proceed(requestBuilder.build())
                }
                .build()

            val request = okhttp3.Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()

            WebResourceResponse(
                "text/event-stream",
                "utf-8",
                response.code(),
                response.message(),
                response.headers().toMultimap() as Map<String?, String?>?,
                response.body()?.byteStream()
            )
        } catch (e: Exception) {
            Log.e(TAG, "拦截SSE请求失败", e)
            null
        }
    }

    /**
     * 处理本地资源请求
     */
    private fun handleAssetRequest(url: String): WebResourceResponse? {
        return try {
            val assetPath = url.removePrefix("file:///android_asset/")
            val mimeType = getMimeType(assetPath)

            // 特殊处理：如果请求的是模块文件，需要处理版本号
            val processedPath = if (assetPath.contains("?v=")) {
                assetPath.substringBefore("?v=")
            } else {
                assetPath
            }

            val inputStream = assets.open(processedPath)

            WebResourceResponse(
                mimeType,
                "utf-8",
                inputStream
            )
        } catch (e: Exception) {
            Log.e(TAG, "加载资源失败: $url", e)
            // 尝试查找替代资源
            handleFallbackResource(url, e)
        }
    }

    /**
     * 处理备用资源
     */
    private fun handleFallbackResource(url: String, originalError: Exception): WebResourceResponse? {
        return try {
            val assetPath = url.removePrefix("file:///android_asset/")

            // 尝试查找不带版本号的资源
            val cleanPath = assetPath.substringBefore("?")
            if (cleanPath != assetPath) {
                val inputStream = assets.open(cleanPath)
                val mimeType = getMimeType(cleanPath)
                return WebResourceResponse(mimeType, "utf-8", inputStream)
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "加载备用资源失败", e)
            null
        }
    }

    /**
     * 处理外部资源请求
     */
    private fun handleExternalResourceRequest(url: String): WebResourceResponse? {
        // 这里可以添加CDN加速、缓存等逻辑
        Log.d(TAG, "外部资源请求: $url")
        return null // 返回null让WebView默认处理
    }

    /**
     * 根据文件扩展名获取MIME类型
     */
    private fun getMimeType(filePath: String): String {
        val extension = filePath.substringAfterLast('.', "")
        return mimeTypes[".$extension"] ?: "application/octet-stream"
    }

    /**
     * 注入应用配置到JavaScript环境
     */
    private fun injectAppConfig() {
        val sessionId = sessionManager.getSessionId()
        val deviceInfo = getDeviceInfo()
        val userId = getUserId()

        val config = """
        window.APP_CONFIG = {
            baseUrl: "https://derbi.net.cn",
            apiBaseUrl: "https://derbi.net.cn",
            sessionId: "${sessionId.ifEmpty { "" }}",
            userId: "$userId",
            platform: "android",
            version: "1.0.0",
            deviceInfo: $deviceInfo,
            isAndroid: true,
            appName: "小夏",
            appVersion: "1.0.0",
            timestamp: ${System.currentTimeMillis()}
        };
        
        // 修复fetch拦截器，确保所有API请求都使用正确的基础URL
        (function() {
            const originalFetch = window.fetch;
            if (originalFetch) {
                window.fetch = function(url, options = {}) {
                    // 处理相对路径
                    let finalUrl = url;
                    if (typeof url === 'string') {
                        if (url.startsWith('/')) {
                            finalUrl = 'https://derbi.net.cn' + url;
                        } else if (url.startsWith('./') || url.startsWith('../')) {
                            // 处理相对路径
                            finalUrl = new URL(url, window.location.origin).href;
                        }
                    }
                    
                    // 添加Android特定头
                    const newOptions = {
                        ...options,
                        headers: {
                            ...options.headers,
                            'X-Platform': 'android',
                            'X-App-Version': '1.0.0',
                            'X-Session-Id': window.APP_CONFIG.sessionId || ''
                        }
                    };
                    
                    return originalFetch.call(this, finalUrl, newOptions);
                };
            }
            
            // 修复XMLHttpRequest
            const originalXHROpen = XMLHttpRequest.prototype.open;
            if (originalXHROpen) {
                XMLHttpRequest.prototype.open = function(method, url, async, user, password) {
                    let finalUrl = url;
                    if (typeof url === 'string') {
                        if (url.startsWith('/')) {
                            finalUrl = 'https://derbi.net.cn' + url;
                        } else if (url.startsWith('./') || url.startsWith('../')) {
                            finalUrl = new URL(url, window.location.origin).href;
                        }
                    }
                    return originalXHROpen.call(this, method, finalUrl, async, user, password);
                };
            }
        })();
        
        // 初始化完成事件
        window.dispatchEvent(new Event('android-app-ready'));
    """.trimIndent()

        webView.evaluateJavascript(config, null)
    }

    /**
     * 更新Web中的sessionId
     */
    private fun updateWebSessionId(sessionId: String) {
        val script = """
            if (window.APP_CONFIG) {
                window.APP_CONFIG.sessionId = "$sessionId";
            }
            if (window.SessionManager) {
                window.SessionManager.saveSessionId("$sessionId");
            }
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    /**
     * 加载聊天页面
     */
    private fun loadChatPage() {
        webView.loadUrl(CHAT_URL)
    }

    /**
     * 打开外部浏览器
     */
    private fun openExternalBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

            // 设置包名，避免在WebView中打开
            intent.setPackage(null)

            // 验证是否有应用可以处理此Intent
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "没有找到可以打开链接的应用", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开外部浏览器失败", e)
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示离线页面
     */
    private fun showOfflinePage() {
        val offlineHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>网络连接失败</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        height: 100vh;
                        margin: 0;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        color: white;
                    }
                    .container {
                        text-align: center;
                        padding: 2rem;
                        max-width: 400px;
                    }
                    h1 {
                        font-size: 2rem;
                        margin-bottom: 1rem;
                    }
                    p {
                        margin-bottom: 2rem;
                        opacity: 0.8;
                    }
                    button {
                        background: white;
                        color: #667eea;
                        border: none;
                        padding: 12px 24px;
                        border-radius: 25px;
                        font-size: 1rem;
                        font-weight: 600;
                        cursor: pointer;
                        transition: transform 0.2s;
                    }
                    button:hover {
                        transform: scale(1.05);
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>⚠️ 网络连接失败</h1>
                    <p>请检查您的网络连接，然后重试</p>
                    <button onclick="location.reload()">重新加载</button>
                </div>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(
            "file:///android_asset/",
            offlineHtml,
            "text/html",
            "UTF-8",
            null
        )
    }

    /**
     * 获取设备信息
     */
    private fun getDeviceInfo(): String {
        return JSONObject().apply {
            put("platform", "Android")
            put("version", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("model", Build.MODEL)
            put("brand", Build.BRAND)
            put("manufacturer", Build.MANUFACTURER)
            put("product", Build.PRODUCT)
            put("device", Build.DEVICE)
            put("screenWidth", resources.displayMetrics.widthPixels)
            put("screenHeight", resources.displayMetrics.heightPixels)
            put("density", resources.displayMetrics.density)
            put("densityDpi", resources.displayMetrics.densityDpi)
            put("locale", Locale.getDefault().toString())
            put("timezone", TimeZone.getDefault().id)
            put("isEmulator", Build.FINGERPRINT.startsWith("generic") ||
                    Build.FINGERPRINT.startsWith("unknown") ||
                    Build.MODEL.contains("google_sdk") ||
                    Build.MODEL.contains("Emulator") ||
                    Build.MODEL.contains("Android SDK"))
        }.toString()
    }

    /**
     * 获取用户ID
     */
    private fun getUserId(): String {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        var userId = prefs.getString("user_id", null)

        if (userId == null) {
            userId = "user_${System.currentTimeMillis()}_${(Math.random() * 1000000).toInt()}"
            prefs.edit().putString("user_id", userId).apply()
        }

        return userId
    }

    /**
     * 检查音频权限
     */
    private fun checkAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 检查相机权限
     */
    private fun checkCameraPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 检查存储权限
     */
    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 请求音频权限
     */
    private fun requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    REQUEST_CODE_AUDIO_PERMISSION
                )
            }
        }
    }

    /**
     * 处理Activity结果
     */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_CODE_FILE_PICKER -> {
                if (resultCode == RESULT_OK) {
                    val results = if (data?.clipData != null) {
                        val count = data.clipData!!.itemCount
                        Array(count) { i ->
                            data.clipData!!.getItemAt(i).uri
                        }
                    } else if (data?.data != null) {
                        arrayOf(data.data!!)
                    } else {
                        emptyArray()
                    }

                    fileChooserCallback?.onReceiveValue(results)
                    fileChooserCallback = null
                } else {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                }
            }
        }
    }

    /**
     * 处理权限请求结果
     */
    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_CODE_AUDIO_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "录音权限已授予", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "录音权限被拒绝", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CODE_STORAGE_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "存储权限被拒绝", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 增强的JavaScript接口类
     */
    @SuppressLint("JavascriptInterface")
    inner class EnhancedWebAppInterface {

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun openLink(url: String) {
            runOnUiThread {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (url.contains("derbi.net.cn")) {
                        // 内部链接，在WebView中加载
                        webView.loadUrl(url)
                    } else {
                        // 外部链接，用浏览器打开
                        openExternalBrowser(url)
                    }
                }
            }
        }

        @JavascriptInterface
        fun getSessionId(): String {
            return sessionManager.getSessionId() ?: ""
        }

        @JavascriptInterface
        fun saveSessionId(sessionId: String) {
            coroutineScope.launch {
                sessionManager.saveSessionId(sessionId)
            }
        }

        @JavascriptInterface
        fun clearSession() {
            coroutineScope.launch {
                sessionManager.clearSessionId()
            }
        }

        @JavascriptInterface
        fun openChat(conversationId: String) {
            runOnUiThread {
                val url = if (conversationId == "new") {
                    CHAT_URL
                } else {
                    "$CHAT_URL?conversationId=$conversationId"
                }
                webView.loadUrl(url)
            }
        }

        @JavascriptInterface
        fun navigateTo(path: String) {
            runOnUiThread {
                when {
                    path.startsWith("http://") || path.startsWith("https://") -> {
                        webView.loadUrl(path)
                    }
                    path.startsWith("file://") -> {
                        webView.loadUrl(path)
                    }
                    else -> {
                        // 相对路径，转换为asset路径
                        val assetUrl = "file:///android_asset/$path"
                        webView.loadUrl(assetUrl)
                    }
                }
            }
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            return getDeviceInfo()
        }

        @JavascriptInterface
        fun getAppConfig(): String {
            val sessionId = sessionManager.getSessionId()
            val deviceInfo = getDeviceInfo()

            return JSONObject().apply {
                put("baseUrl", "https://derbi.net.cn")
                put("sessionId", sessionId)
                put("platform", "android")
                put("version", "1.0.0")
                put("deviceInfo", JSONObject(deviceInfo))
                put("userId", getUserId())
            }.toString()
        }

        @JavascriptInterface
        fun setStorage(key: String, value: String) {
            val prefs = getSharedPreferences("web_storage", Context.MODE_PRIVATE)
            prefs.edit().putString(key, value).apply()
        }

        @JavascriptInterface
        fun getStorage(key: String): String {
            val prefs = getSharedPreferences("web_storage", Context.MODE_PRIVATE)
            return prefs.getString(key, "") ?: ""
        }

        @JavascriptInterface
        fun removeStorage(key: String) {
            val prefs = getSharedPreferences("web_storage", Context.MODE_PRIVATE)
            prefs.edit().remove(key).apply()
        }

        @JavascriptInterface
        fun clearStorage() {
            val prefs = getSharedPreferences("web_storage", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        }

        @JavascriptInterface
        fun isOnline(): Boolean {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnected
        }

        @JavascriptInterface
        fun getNetworkType(): String {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkInfo = connectivityManager.activeNetworkInfo

            return when (networkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "mobile"
                ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                else -> "unknown"
            }
        }

        @JavascriptInterface
        fun canGoBack(): Boolean {
            return webView.canGoBack()
        }

        @JavascriptInterface
        fun goBack() {
            runOnUiThread {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        }

        @JavascriptInterface
        fun reload() {
            runOnUiThread {
                webView.reload()
            }
        }

        @JavascriptInterface
        fun pickFile(acceptTypes: String) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (acceptTypes.contains("image")) "image/*" else "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.split(",").toTypedArray())
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                try {
                    startActivityForResult(intent, REQUEST_CODE_FILE_PICKER)
                } catch (e: Exception) {
                    Log.e(TAG, "打开文件选择器失败", e)
                    Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun startRecording(callbackName: String) {
            runOnUiThread {
                if (!checkAudioPermission()) {
                    requestAudioPermission()
                    Toast.makeText(this@MainActivity, "请先授予录音权限", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                if (!isRecording) {
                    isRecording = true
                    // 这里实现录音逻辑
                    Toast.makeText(this@MainActivity, "开始录音", Toast.LENGTH_SHORT).show()

                    // 模拟录音结果
                    webView.postDelayed({
                        val text = "这是模拟的语音识别结果"
                        val script = "if (window.$callbackName) window.$callbackName('$text');"
                        webView.evaluateJavascript(script, null)
                        isRecording = false
                    }, 2000)
                }
            }
        }

        @JavascriptInterface
        fun stopRecording() {
            runOnUiThread {
                if (isRecording) {
                    isRecording = false
                    Toast.makeText(this@MainActivity, "停止录音", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }

                try {
                    startActivity(Intent.createChooser(intent, "分享"))
                } catch (e: Exception) {
                    Log.e(TAG, "分享失败", e)
                }
            }
        }

        @JavascriptInterface
        fun vibrate(duration: Int) {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator?
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration.toLong(),
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration.toLong())
                }
            }
        }

        @JavascriptInterface
        fun showNotification(title: String, message: String) {
            // 这里实现通知功能
            Log.d(TAG, "显示通知: $title - $message")
        }

        @JavascriptInterface
        fun onPageLoaded(url: String, title: String) {
            Log.d(TAG, "页面加载完成: $url, 标题: $title")
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d("WebApp", message)
        }

        @JavascriptInterface
        fun error(message: String) {
            Log.e("WebApp", message)
        }

        @JavascriptInterface
        fun getAppVersion(): String? {
            return try {
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                packageInfo.versionName
            } catch (e: Exception) {
                "1.0.0"
            }
        }

        @JavascriptInterface
        fun exitApp() {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 取消所有进行中的请求
        activeRequests.values.forEach { it.cancel() }
        activeRequests.clear()
    }
}