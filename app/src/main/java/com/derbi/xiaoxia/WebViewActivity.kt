package com.derbi.xiaoxia.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.derbi.xiaoxia.R
import com.google.android.material.appbar.MaterialToolbar

class WebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL_TO_LOAD = "url_to_load"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var toolbar: MaterialToolbar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        toolbar = findViewById(R.id.toolbar)
        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress_bar)

        setupToolbar()
        setupWebView()
        setupBackPressedHandler()
        loadUrl()
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)

            // 设置标题
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "链接"
            setTitle(title)
        }

        // 使用 OnBackPressedDispatcher 替代已弃用的 onBackPressed()
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupBackPressedHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // 如果 WebView 无法返回，则关闭当前 Activity
                    isEnabled = false
                    finish()
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings = webView.settings
        webSettings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            loadsImagesAutomatically = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url.toString()
                // 处理链接点击，保持在同一WebView中打开
                view?.loadUrl(url)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = android.view.View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = android.view.View.GONE

                // 更新标题为网页标题
                val pageTitle = view?.title
                if (!pageTitle.isNullOrEmpty()) {
                    supportActionBar?.title = pageTitle
                }
            }
        }
    }

    private fun loadUrl() {
        val urlToLoad = intent.getStringExtra(EXTRA_URL_TO_LOAD)
        if (!urlToLoad.isNullOrBlank()) {
            webView.loadUrl(urlToLoad)
        } else {
            webView.loadUrl("https://about:blank")
        }
    }



    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_webview, menu)
        return true
    }



    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                webView.reload()
                true
            }
            R.id.action_share -> {
                shareUrl()
                true
            }
            R.id.action_open_in_browser -> {
                openInBrowser()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun shareUrl() {
        val url = webView.url ?: return
        val shareIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, url)
        }
        startActivity(android.content.Intent.createChooser(shareIntent, "分享链接"))
    }

    private fun openInBrowser() {
        val url = webView.url ?: return
        val browserIntent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url)
        )
        startActivity(browserIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}