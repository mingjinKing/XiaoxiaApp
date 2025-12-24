package com.derbi.xiaoxia

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.request.RequestOptions
import com.derbi.xiaoxia.adapters.Suggestion
import com.derbi.xiaoxia.adapters.WelcomeSuggestionAdapter
import com.derbi.xiaoxia.models.Conversation
import com.derbi.xiaoxia.models.Message
import com.derbi.xiaoxia.network.ApiService
import com.derbi.xiaoxia.repository.ChatRepository
import com.derbi.xiaoxia.repository.impl.SessionManagerImpl
import com.derbi.xiaoxia.ui.SettingsActivity
import com.derbi.xiaoxia.ui.WebViewActivity
import com.derbi.xiaoxia.utils.DateTypeAdapter
import com.derbi.xiaoxia.viewmodel.ChatViewModel
import com.derbi.xiaoxia.viewmodel.ChatViewModelFactory
import com.google.android.material.navigation.NavigationView
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var webView: WebView
    private lateinit var inputEditText: EditText
    private lateinit var fabSend: ImageButton
    private lateinit var btnDeepThinking: TextView
    private lateinit var btnWebSearch: TextView
    private lateinit var toolbarTitle: TextView

    private lateinit var welcomeContainer: View

    private lateinit var viewModel: ChatViewModel

    private var isSending = false

    private var backPressedTime: Long = 0

    private var isUserScrolling = false

    private lateinit var profileImageView: ImageView

    private lateinit var navProfileImageView: ImageView

    private lateinit var conversationRecyclerView: RecyclerView
    private lateinit var conversationAdapter: ConversationAdapter
    private var longPressConversationId: String? = null
    private var longPressTimer: Timer? = null

    // 主题切换相关
    private var currentColorIndex = 0
    private val themeColors = listOf(R.color.theme_white, R.color.theme_blue, R.color.theme_pink)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val gson = GsonBuilder()
            .registerTypeAdapter(Date::class.java, DateTypeAdapter())
            .setLenient()
            .create()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://derbi.net.cn")
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        val apiService = retrofit.create(ApiService::class.java)
        val sessionManager = SessionManagerImpl(applicationContext)
        val chatRepository = ChatRepository(apiService, sessionManager)
        val viewModelFactory = ChatViewModelFactory(chatRepository, sessionManager)
        viewModel = ViewModelProvider(this, viewModelFactory).get(ChatViewModel::class.java)

        initViews()
        setupSettingButton()
        setupWelcomeGrid()
        setupWebView()
        setupClickListeners()
        setupInputListener()
        setupBackPressedHandler()
        setupConversationList()

        loadProfileImage()
        startCollectingState()

        viewModel.initializeApp()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    private fun startCollectingState() {
        lifecycleScope.launch {
            viewModel.chatState.collect { state ->
                updateMessages(state.messages)
                checkChatState(state.showWelcome, state.messages.isEmpty())
                updateLoadingState(state.isSending, state.isLoading)

                updateConversationTitle(state.selectedConversation?.title)

                btnDeepThinking.isSelected = state.deepThinkingEnabled
                btnWebSearch.isSelected = state.webSearchEnabled
            }
        }

        lifecycleScope.launch {
            viewModel.conversationsState.collect { state ->
                updateConversationList(state.conversations)
            }
        }
    }

    private fun updateLoadingState(isSending: Boolean, isLoading: Boolean) {
        this.isSending = isSending
        fabSend.isEnabled = !isSending
        if (isSending) {
            fabSend.alpha = 0.5f
        } else {
            fabSend.setImageResource(R.drawable.ic_send)
            fabSend.alpha = 1.0f
        }
    }

    private fun updateConversationTitle(title: String?) {
        toolbarTitle.text = title ?: "新对话"
    }

    private fun updateMessages(messages: List<Message>) {
        updateWebViewMessages(messages)
    }

    private fun updateWebViewMessages(messages: List<Message>) {
        if (!::webView.isInitialized || messages.isEmpty()) return

        val messagesJson = messages.map { msg ->
            when (msg) {
                is Message.AIMessage -> mapOf(
                    "id" to msg.id,
                    "type" to "ai",
                    "content" to msg.content,
                    "reasoningContent" to msg.reasoningContent,
                    "showReasoning" to msg.showReasoning,
                    "isReceiving" to msg.isReceiving,
                    "timestamp" to msg.timestamp,
                    "showDisclaimer" to msg.showDisclaimer
                )
                is Message.UserMessage -> mapOf(
                    "id" to msg.id,
                    "type" to "user",
                    "content" to msg.content,
                    "timestamp" to msg.timestamp
                )
                else -> mapOf("type" to "loading")
            }
        }

        val jsonString = Gson().toJson(messagesJson)
        runOnUiThread {
            webView.evaluateJavascript("syncMessages($jsonString);", null)
        }
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.navigation_view)
        inputEditText = findViewById(R.id.input_message)
        fabSend = findViewById(R.id.fab_send)
        btnDeepThinking = findViewById(R.id.btn_deep_thinking)
        btnWebSearch = findViewById(R.id.btn_web_search)
        toolbarTitle = findViewById(R.id.toolbar_title)
        profileImageView = findViewById(R.id.profile_image)

        webView = findViewById(R.id.webview_messages)
        setupWebView()

        val headerView = navigationView.getHeaderView(0)
        conversationRecyclerView = headerView.findViewById(R.id.recycler_conversations)

        navigationView.setNavigationItemSelectedListener(this)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
            viewModel.fetchConversations(true)
        }

        welcomeContainer = findViewById(R.id.welcome_container)

        // 初始化时立即同步主题
        changeThemeColor(themeColors[currentColorIndex])

        // 点击标题切换主题颜色
        toolbarTitle.setOnClickListener {
            currentColorIndex = (currentColorIndex + 1) % themeColors.size
            changeThemeColor(themeColors[currentColorIndex])
        }
    }

    /**
     * 动态切换顶部导航栏及侧边栏 Header 颜色主题
     */
    private fun changeThemeColor(colorResId: Int) {
        val color = ContextCompat.getColor(this, colorResId)
        val appBar = findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.app_bar)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)

        // 1. 设置首页顶部背景色
        appBar.setBackgroundColor(color)
        toolbar.setBackgroundColor(color)
        window.statusBarColor = color

        // 2. 处理侧边栏 Header 同步
        val headerView = navigationView.getHeaderView(0)
        val navHeaderRoot = headerView.findViewById<View>(R.id.nav_header_root)
        val navName = headerView.findViewById<TextView>(R.id.textView_name)
        val navMotto = headerView.findViewById<TextView>(R.id.textView_motto)
        val navMoreBtn = headerView.findViewById<ImageButton>(R.id.btn_more)
        val historyLabel = headerView.findViewById<TextView>(R.id.textView_history_label)


        navHeaderRoot?.setBackgroundColor(color)

        // 3. 根据背景色调整文字和图标颜色
        val isWhiteTheme = colorResId == R.color.theme_white
        val contentColor = if (isWhiteTheme) Color.BLACK else Color.WHITE
        
        // 首页顶部同步
        toolbarTitle.setTextColor(contentColor)
        toolbar.navigationIcon?.setTint(contentColor)
        toolbar.overflowIcon?.setTint(contentColor)
        for (i in 0 until toolbar.menu.size()) {
            toolbar.menu.getItem(i).icon?.setTint(contentColor)
        }

        // 侧边栏 Header 内容同步
        navName?.setTextColor(contentColor)
        navMotto?.setTextColor(contentColor)
        navMotto?.alpha = if (isWhiteTheme) 0.6f else 0.8f
        navMoreBtn?.imageTintList = android.content.res.ColorStateList.valueOf(contentColor)
        
        // “对话历史”标签颜色根据主题动态调整
        historyLabel?.setTextColor(if (isWhiteTheme) ContextCompat.getColor(this, R.color.text_secondary) else color)

        // 4. 调整系统状态栏图标颜色
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = isWhiteTheme
    }

    private fun setupWebView() {
        webView.apply {
            setBackgroundColor(0)
            settings.apply {
                javaScriptEnabled = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                domStorageEnabled = true
                allowFileAccess = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportMultipleWindows(true)
            }
        }

        webView.addJavascriptInterface(WebAppInterface(), "AndroidInterface")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                url?.let {
                    if (it.startsWith("http://") || it.startsWith("https://")) {
                        openLinkInWebView(it, "链接")
                        return true
                    }
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (viewModel.chatState.value.messages.isNotEmpty()) {
                    updateWebViewMessages(viewModel.chatState.value.messages)
                }
            }
        }

        webView.loadUrl("file:///android_asset/chat.html")
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun openLink(url: String) {
            runOnUiThread {
                openLinkInWebView(url, "链接")
            }
        }
    }

    private fun loadProfileImage() {
        try {
            loadProfileWithGlide()
        } catch (e: Exception) {
            Log.e("ProfileImage", "加载头像失败: ${e.message}")
        }
    }

    private fun loadProfileWithGlide() {
        try {
            Glide.with(this)
                .asBitmap()
                .load(R.drawable.profile)
                .apply(RequestOptions.bitmapTransform(CircleCrop()))
                .into(profileImageView)

            if (!::navProfileImageView.isInitialized) {
                val headerView = navigationView.getHeaderView(0)
                if (headerView != null) {
                    navProfileImageView = headerView.findViewById(R.id.imageView_avatar)
                }
            }

            if (::navProfileImageView.isInitialized) {
                Glide.with(this)
                    .asBitmap()
                    .load(R.drawable.profile)
                    .into(navProfileImageView)
            }
        } catch (e: Exception) {
            Log.e("ProfileImage", "Glide 加载失败: ${e.message}")
            profileImageView.setImageResource(R.drawable.profile)
            if (::navProfileImageView.isInitialized) {
                navProfileImageView.setImageResource(R.drawable.profile)
            }
        }
    }

    private fun setupWelcomeGrid() {
        val recyclerViewExamples: RecyclerView = findViewById(R.id.recycler_examples)
        recyclerViewExamples.layoutManager = GridLayoutManager(this, 2)

        val suggestions = listOf(
            Suggestion("快速复盘", "帮我复盘一下本周的工作和学习"),
            Suggestion("原则查询", "请解释P10长远家庭利益原则"),
            Suggestion("规划建议", "为我制定下周的学习计划"),
            Suggestion("目标设定", "如何设定我的3年职业发展目标？")
        )

        val adapter = WelcomeSuggestionAdapter(suggestions) { text ->
            inputEditText.setText(text)
            sendMessage()
        }

        recyclerViewExamples.adapter = adapter
    }

    private fun checkChatState(showWelcome: Boolean, isEmpty: Boolean) {
        if (showWelcome || isEmpty) {
            welcomeContainer.visibility = View.VISIBLE
            webView.visibility = View.INVISIBLE
        } else {
            welcomeContainer.visibility = View.GONE
            webView.visibility = View.VISIBLE
        }
    }

    private fun setupBackPressedHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    return
                }
                handleBackPress()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun handleBackPress() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - backPressedTime in 0..2000) {
            finish()
        } else {
            showToast("再按一次退出")
            backPressedTime = currentTime
        }
    }

    private fun setupClickListeners() {
        fabSend.setOnClickListener {
            sendMessage()
        }

        findViewById<View>(R.id.btn_voice_input).setOnClickListener {
            showToast("语音输入功能开发中...")
        }

        findViewById<View>(R.id.btn_attachment).setOnClickListener {
            showToast("附件上传功能开发中...")
        }

        btnDeepThinking.setOnClickListener {
            val newState = !it.isSelected
            it.isSelected = newState
            viewModel.updateDeepThinking(newState)
        }

        btnWebSearch.setOnClickListener {
            val newState = !it.isSelected
            it.isSelected = newState
            viewModel.updateWebSearch(newState)
        }
    }

    private fun setupInputListener() {
        inputEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                fabSend.isEnabled = !s.isNullOrBlank()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        inputEditText.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                if (!event.isShiftPressed) {
                    sendMessage()
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    private fun sendMessage() {
        val userInput = inputEditText.text.toString().trim()
        if (userInput.isEmpty() || isSending) return

        inputEditText.text.clear()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inputEditText.windowToken, 0)

        isUserScrolling = false
        forceScrollToBottom()
        viewModel.sendMessage(userInput)
    }

    private fun forceScrollToBottom() {
        webView.evaluateJavascript("scrollToBottom();", null)
    }

    private fun openLinkInWebView(url: String, title: String = "链接") {
        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL_TO_LOAD, url)
            putExtra(WebViewActivity.EXTRA_TITLE, title)
        }
        startActivity(intent)
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_chat -> {
                viewModel.clearMessages()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupConversationList() {
        conversationAdapter = ConversationAdapter { conversation ->
            loadConversation(conversation)
        }

        conversationRecyclerView.layoutManager = LinearLayoutManager(this)
        conversationRecyclerView.adapter = conversationAdapter

        conversationRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0) {
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 2
                        && firstVisibleItemPosition >= 0) {
                        viewModel.fetchConversations(false)
                    }
                }
            }
        })
    }

    private fun setupSettingButton() {
        val headerView = navigationView.getHeaderView(0)
        
        val openSettings = {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.putExtra("theme_color_res", themeColors[currentColorIndex])
            startActivity(intent)
        }

        headerView.findViewById<ImageButton>(R.id.btn_more).setOnClickListener {
            openSettings()
        }

        // 处理头像点击
        headerView.findViewById<View>(R.id.nav_avatar_container)?.setOnClickListener {
            openSettings()
        }

        // 处理文字点击
        headerView.findViewById<View>(R.id.nav_text_container)?.setOnClickListener {
            openSettings()
        }
    }

    private fun updateConversationList(conversations: List<Conversation>) {
        conversationAdapter.submitList(conversations)
    }

    private fun loadConversation(conversation: Conversation) {
        viewModel.loadConversation(conversation.id)
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    inner class ConversationAdapter(
        private val onItemClick: (Conversation) -> Unit
    ) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

        private var conversations = mutableListOf<Conversation>()
        private var selectedPosition = -1

        fun submitList(newList: List<Conversation>) {
            conversations.clear()
            conversations.addAll(newList.distinctBy { it.id })
            notifyDataSetChanged()
        }

        fun selectConversation(conversationId: String?) {
            val newPosition = conversations.indexOfFirst { it.id == conversationId }
            val oldPosition = selectedPosition
            selectedPosition = newPosition
            if (oldPosition != -1) notifyItemChanged(oldPosition)
            if (selectedPosition != -1) notifyItemChanged(selectedPosition)
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.text_title)
            val summary: TextView = itemView.findViewById(R.id.text_summary)
            val time: TextView = itemView.findViewById(R.id.text_time)
            val deleteBtn: Button = itemView.findViewById(R.id.btn_delete)
            val indicator: View = itemView.findViewById(R.id.indicator_selected)
            val root: RelativeLayout = itemView.findViewById(R.id.root)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_conversation, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val conversation = conversations[position]
            holder.title.text = conversation.title
            holder.summary.text = conversation.summary
            holder.time.text = formatTime(conversation.updatedAt)

            holder.indicator.visibility = if (position == selectedPosition) View.VISIBLE else View.GONE
            holder.root.isSelected = position == selectedPosition

            holder.root.setOnClickListener {
                onItemClick(conversation)
                selectConversation(conversation.id)
            }

            holder.root.setOnLongClickListener {
                showDeleteButton(holder, conversation)
                true
            }

            holder.deleteBtn.setOnClickListener {
                deleteConversation(conversation)
                hideDeleteButton(holder)
            }
        }

        override fun getItemCount(): Int = conversations.size
    }

    private fun showDeleteButton(holder: ConversationAdapter.ViewHolder, conversation: Conversation) {
        holder.deleteBtn.visibility = View.VISIBLE
        longPressConversationId = conversation.id
        longPressTimer?.cancel()
        longPressTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    runOnUiThread {
                        if (longPressConversationId == conversation.id) {
                            hideDeleteButton(holder)
                        }
                    }
                }
            }, 5000)
        }
    }

    private fun hideDeleteButton(holder: ConversationAdapter.ViewHolder) {
        holder.deleteBtn.visibility = View.GONE
        longPressConversationId = null
        longPressTimer?.cancel()
    }

    private fun deleteConversation(conversation: Conversation) {
        viewModel.deleteConversation(conversation.id)
    }

    private fun formatTime(timestamp: Date): String {
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return sdf.format(timestamp)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
}
