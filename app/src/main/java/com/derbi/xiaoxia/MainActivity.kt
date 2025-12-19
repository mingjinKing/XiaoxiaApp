package com.derbi.xiaoxia

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.request.RequestOptions
import com.derbi.xiaoxia.adapters.MessageAdapter
import com.derbi.xiaoxia.adapters.Suggestion
import com.derbi.xiaoxia.adapters.WelcomeSuggestionAdapter
import com.derbi.xiaoxia.models.Conversation
import com.derbi.xiaoxia.models.Message
import com.derbi.xiaoxia.network.ApiService
import com.derbi.xiaoxia.repository.ChatRepository
import com.derbi.xiaoxia.repository.impl.SessionManagerImpl
import com.derbi.xiaoxia.ui.WebViewActivity
import com.derbi.xiaoxia.utils.DateTypeAdapter
import com.derbi.xiaoxia.viewmodel.ChatViewModel
import com.derbi.xiaoxia.viewmodel.ChatViewModelFactory
import com.google.android.material.navigation.NavigationView
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.delay
import java.io.IOException
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
    private lateinit var messageRecyclerView: RecyclerView
    private lateinit var inputEditText: EditText
    private lateinit var fabSend: ImageButton
    private lateinit var switchDeepThinking: SwitchCompat
    private lateinit var switchWebSearch: SwitchCompat
    private lateinit var toolbarTitle: TextView

    private lateinit var messageAdapter: MessageAdapter

    private lateinit var welcomeContainer: View

    private lateinit var viewModel: ChatViewModel

    private var isSending = false

    private var backPressedTime: Long = 0

    private var isUserScrolling = false

    private lateinit var profileImageView: ImageView  // 添加头像 ImageView 引用

    private lateinit var navProfileImageView: ImageView  // 添加头像 ImageView 引用

    private lateinit var conversationRecyclerView: RecyclerView
    private lateinit var conversationAdapter: ConversationAdapter
    private var longPressConversationId: String? = null
    private var longPressTimer: Timer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        setContentView(R.layout.activity_main)

        val gson = GsonBuilder()
            .registerTypeAdapter(Date::class.java, DateTypeAdapter())
            .setLenient()
            .create()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://derbi.net.cn") // Replace with your base URL
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        val apiService = retrofit.create(ApiService::class.java)
        val sessionManager = SessionManagerImpl(applicationContext)
        val chatRepository = ChatRepository(apiService, sessionManager)
        val viewModelFactory = ChatViewModelFactory(chatRepository, sessionManager)
        viewModel = ViewModelProvider(this, viewModelFactory).get(ChatViewModel::class.java)

        initViews()
        setupMoreButton() // 新增：设置更多按钮
        setupWelcomeGrid()
        setupRecyclerView()
        setupClickListeners()
        setupInputListener()
        setupBackPressedHandler()
        setupConversationList() // 新增：初始化对话列表

        // 加载头像图片
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

                switchDeepThinking.isChecked = state.deepThinkingEnabled
                switchWebSearch.isChecked = state.webSearchEnabled
            }
        }

        lifecycleScope.launch {
            viewModel.conversationsState.collect { state ->
                updateConversationList(state.conversations)
            }
        }
    }

    private fun updateLoadingState(isSending: Boolean, isLoading: Boolean) {
        //Log.d("MainActivity", "updateLoadingState: isSending=$isSending, isLoading=$isLoading")

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
        messageAdapter.submitList(messages.toList())
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.navigation_view)
        messageRecyclerView = findViewById(R.id.recyclerView_messages)
        inputEditText = findViewById(R.id.input_message)
        fabSend = findViewById(R.id.fab_send)
        switchDeepThinking = findViewById(R.id.switch_deep_thinking)
        switchWebSearch = findViewById(R.id.switch_web_search)
        toolbarTitle = findViewById(R.id.toolbar_title)
        profileImageView = findViewById(R.id.profile_image)  // 初始化头像 ImageView

        // 获取 HeaderView (通常是第 0 个)
        val headerView = navigationView.getHeaderView(0)

        // 从 headerView 中查找 RecyclerView
        conversationRecyclerView = headerView.findViewById(R.id.recycler_conversations)

        navigationView.setNavigationItemSelectedListener(this)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
            viewModel.fetchConversations(false)
        }

        welcomeContainer = findViewById(R.id.welcome_container)
    }

    /**
     * 加载头像图片
     */
    private fun loadProfileImage() {
        try {
            // 方法1：使用 Glide 加载图片（推荐）
            loadProfileWithGlide()
        } catch (e: Exception) {
            Log.e("ProfileImage", "加载头像失败: ${e.message}")
        }
    }

    /**
     * 使用 Glide 加载头像（最可靠的方法）
     */
    private fun loadProfileWithGlide() {
        // 检查是否安装了 Glide
        try {
            Glide.with(this)
                .asBitmap() // 确保作为Bitmap加载，而不是可能被当作视频
                .load(R.drawable.profile)
                .apply(RequestOptions.bitmapTransform(CircleCrop()))
                .into(profileImageView)

            // 2. 导航栏头像：延迟初始化
            // 先确保 navigationView 已经初始化了头部视图
            if (!::navProfileImageView.isInitialized) {
                val headerView = navigationView.getHeaderView(0)
                if (headerView != null) {
                    navProfileImageView = headerView.findViewById(R.id.imageView_avatar)
                }
            }

            // 如果成功初始化了，就加载图片
            if (::navProfileImageView.isInitialized) {
                Glide.with(this)
                    .asBitmap() // 确保作为Bitmap加载，而不是可能被当作视频
                    .load(R.drawable.profile)
                    .into(navProfileImageView)
            } else {
                Log.w("ProfileImage", "无法初始化导航栏头像 ImageView")
                // 尝试直接使用 XML 中设置的图片
            }

            Log.d("ProfileImage", "使用 Glide 加载头像成功")
        } catch (e: Exception) {
            // 降级方案
            Log.e("ProfileImage", "Glide 加载失败: ${e.message}")
            profileImageView.setImageResource(R.drawable.profile)
            navProfileImageView.setImageResource(R.drawable.profile)
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
            messageRecyclerView.visibility = View.GONE
        } else {
            welcomeContainer.visibility = View.GONE
            messageRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun setupRecyclerView() {
        val linearLayoutManager = LinearLayoutManager(this)
        linearLayoutManager.stackFromEnd = true
        messageRecyclerView.layoutManager = linearLayoutManager

        messageAdapter = MessageAdapter { url ->
            openLinkInWebView(url, "链接")
        }
        messageRecyclerView.adapter = messageAdapter

        // 禁用 RecyclerView 的更新动画，防止 AI 消息流式输出时产生闪烁效果
        (messageRecyclerView.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false

        // 修改后的滚动监听器
        messageRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var isDragging = false
            private var lastScrollY = 0

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)

                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        if(!isAtMessageBottom()){
                            // 用户开始拖拽
                            isUserScrolling = true
                            isDragging = true
                            recyclerView.post {
                                linearLayoutManager.stackFromEnd = false
                            }
                            Log.d("MainActivity", "用户开始拖拽未到底部，锁定滚动")
                        }else{
                            // 用户开始拖拽
                            isUserScrolling = false
                            isDragging = true
                            recyclerView.post {
                                linearLayoutManager.stackFromEnd = true
                            }
                            Log.d("MainActivity", "用户开始拖拽到底部，解开滚动锁定")
                        }

                    }
                    RecyclerView.SCROLL_STATE_SETTLING -> {
                        // 惯性滚动中
                        isDragging = false
                        Log.d("MainActivity", "惯性滚动中")
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        isDragging = false
                        // 检查是否滚动到底部附近
                        if (isAtMessageBottom()) {
                            // 如果用户在底部附近，解除滚动锁定，允许自动滚动
                            isUserScrolling = false
                            recyclerView.post {
                                linearLayoutManager.stackFromEnd = true
                            }
                            Log.d("MainActivity", "滚动到底部，解锁自动滚动")
                        }
                        // 如果停在中间位置，保持锁定状态
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                // 记录滚动方向
                lastScrollY = dy

                // 如果用户手动向下滚动（查看更早的消息），保持锁定
                if (dy < 0 && isDragging) {
                    isUserScrolling = true
                    recyclerView.post {
                        linearLayoutManager.stackFromEnd = false
                    }
                    Log.d("MainActivity", "用户向上滚动查看历史，保持锁定")
                }

                // 如果用户手动滚动到底部，解锁
                if (isDragging && isAtMessageBottom()) {
                    isUserScrolling = false
                    recyclerView.post {
                        linearLayoutManager.stackFromEnd = true
                    }
                    Log.d("MainActivity", "用户手动滚动到底部，解锁")
                }
            }
        })

        // 修改适配器数据观察器
        messageAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                super.onItemRangeChanged(positionStart, itemCount)

                // 只有在以下情况下才自动滚动：
                // 1. 用户没有手动滚动
                // 2. 或者用户在底部附近（愿意查看最新内容）
                if (!shouldAutoScroll()) {
                    isUserScrolling = true
                    messageRecyclerView.post {
                        linearLayoutManager.stackFromEnd = false
                    }
                    //Log.d("MainActivity", "用户正在查看历史，跳过自动滚动")
                    return
                }

                val isLastMessage = positionStart + itemCount >= messageAdapter.itemCount
                /*if (isLastMessage) {
                    Log.d("MainActivity", "最后一条消息更新，自动滚动")
                    messageRecyclerView.post {
                        messageRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                    }
                }*/
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)

                // 新消息插入时，检查是否应该滚动
                if (shouldAutoScroll()) {
                    Log.d("MainActivity", "新消息插入，自动滚动")
                    isUserScrolling = false
                    messageRecyclerView.post {
                        linearLayoutManager.stackFromEnd = true
                        messageRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                    }
                }
            }
        })

        // 监听 AI 消息的流式输出状态
        lifecycleScope.launch {
            viewModel.chatState.collect { state ->
                if (state.messages.isNotEmpty()) {
                    val lastMessage = state.messages.last()
                    if (lastMessage is Message.AIMessage && lastMessage.isReceiving) {
                        // 只有在应该自动滚动时才滚动
                        if (shouldAutoScroll()) {
                            messageRecyclerView.post {
                                isUserScrolling = false
                                linearLayoutManager.stackFromEnd = true
                                scrollToBottomIfNeeded()
                            }
                        }
                    }
                }
            }
        }
    }

    // 修改 shouldAutoScroll 方法，添加更智能的判断
    private fun shouldAutoScroll(): Boolean {
        // 如果用户明确锁定了滚动，不自动滚动
        if (isUserScrolling) {
            // 检查用户是否在底部附近
            if (isAtMessageBottom()) {
                // 用户在底部附近，表示他们愿意查看最新内容
                return true
            }
            // 用户在查看历史，不自动滚动
            return false
        }

        // 用户没有锁定，自动滚动
        return true
    }

    /**
     * 检查用户是否在查看当前可见的长消息的底部
     * 适用于：长消息场景，用户可能在查看某条消息的中间或末尾
     */
    private fun isAtMessageBottom(): Boolean {
        val layoutManager = messageRecyclerView.layoutManager as? LinearLayoutManager ?: return true

        val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
        val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()

        // 如果可见范围内只有一条消息（长消息）
        if (firstVisiblePosition == lastVisiblePosition) {
            val visibleItem = layoutManager.findViewByPosition(firstVisiblePosition)
            if (visibleItem != null) {
                // 检查这条消息是否滚动到了底部
                Log.d("MainActivity", "检查这条消息是否滚动到了底部, bottomOffset=${visibleItem.bottom}, height=${messageRecyclerView.height}")
                val bottomOffset = messageRecyclerView.height - visibleItem.bottom
                // 允许20像素的误差范围
                return bottomOffset in -40..40
            }
        }

        // 如果可见范围内有多条消息，使用原来的逻辑
        return false;
    }

   /* // 简化滚动到底部的方法
    private fun scrollToBottomIfNeeded() {
        if (shouldAutoScroll()) {
            val layoutManager = messageRecyclerView.layoutManager as? LinearLayoutManager
            val totalItemCount = messageAdapter.itemCount

            if (totalItemCount > 0 && layoutManager != null) {
                // 直接滚动到最后，不使用动画避免闪烁
                layoutManager.scrollToPositionWithOffset(totalItemCount - 1, 0)
            }
        }
    }*/

    // 智能滚动到底部
    private fun scrollToBottomIfNeeded() {
        Log.d("MainActivity", "scrollToBottomIfNeeded, isUserScrolling=$isUserScrolling")
        if (isUserScrolling) return // 用户正在手动操作，系统绝不干预

        val totalItemCount = messageAdapter.itemCount
        if (totalItemCount > 0) {
            val lastMessage = viewModel.chatState.value.messages.lastOrNull()
            val isStreaming = lastMessage is Message.AIMessage && lastMessage.isReceiving

            if (isStreaming) {
                // 流式输出中，直接定位，不使用 smoothScroll 避免动画冲突
                messageRecyclerView.scrollToPosition(totalItemCount - 1)
            } else {
                scrollToBottomSmooth()
            }
        }
    }

    // 平滑滚动到底部
    private fun scrollToBottomSmooth() {
        val totalItemCount = messageAdapter.itemCount
        if (totalItemCount > 0) {
            messageRecyclerView.post {
                try {
                    if (!isAtMessageBottom()) {
                        messageRecyclerView.smoothScrollToPosition(totalItemCount - 1)
                    }
                } catch (e: Exception) {
                    messageRecyclerView.scrollToPosition(totalItemCount - 1)
                }
            }
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

        switchDeepThinking.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateDeepThinking(isChecked)
        }

        switchWebSearch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateWebSearch(isChecked)
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
        Log.d("MainActivity", "尝试发送消息: $userInput, isSending=$isSending")

        if (userInput.isEmpty()) {
            Log.d("MainActivity", "消息为空，不发送")
            return
        }

        if (isSending) {
            Log.d("MainActivity", "正在发送中，跳过")
            return
        }

        if (welcomeContainer.visibility == View.VISIBLE) {
            welcomeContainer.visibility = View.GONE
            messageRecyclerView.visibility = View.VISIBLE
        }

        inputEditText.text.clear()

        // Hide the keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inputEditText.windowToken, 0)

        // 重置滚动状态，允许自动滚动
        isUserScrolling = false
        // 强制滚动到底部，确保新消息可见
        Log.d("MainActivity", "sendMessage 调用 forceScrollToBottom")
        forceScrollToBottom()

        Log.d("MainActivity", "调用 viewModel.sendMessage")
        viewModel.sendMessage(userInput)
    }

    private fun forceScrollToBottom() {
        val adapter = messageRecyclerView.adapter ?: return
        val itemCount = adapter.itemCount
        if (itemCount > 0) {
            messageRecyclerView.post {
                val layoutManager = messageRecyclerView.layoutManager as? LinearLayoutManager
                if (layoutManager != null) {
                    // 1. 先快速定位到最后一条
                    layoutManager.scrollToPositionWithOffset(itemCount - 1, 0)

                    // 2. 关键：在极短时间后再次校验，确保高度更新后依然在底部
                    messageRecyclerView.postDelayed({
                        // 使用 smoothScroll 触发二次修正，并强制滚动到准确的最后一个 item
                        messageRecyclerView.smoothScrollToPosition(itemCount - 1)
                    }, 100)
                }
            }
        }
    }

    private fun clearMessages() {
        viewModel.clearMessages()
    }

    private fun openLinkInWebView(url: String, title: String = "链接") {
        Log.d("MainActivity", "打开链接: $url")
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
            // 点击加载对话
            loadConversation(conversation)
        }

        conversationRecyclerView.layoutManager = LinearLayoutManager(this)
        conversationRecyclerView.adapter = conversationAdapter
    }

    private fun setupMoreButton() {
        val headerView = navigationView.getHeaderView(0)
        val moreButton = headerView.findViewById<ImageButton>(R.id.btn_more)

        moreButton.setOnClickListener {
            showMoreMenu(it)
        }
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.more_menu, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_new_chat -> {
                    clearMessages()
                    true
                }
                R.id.menu_settings -> {
                    showToast("设置功能开发中...")
                    true
                }
                R.id.menu_about -> {
                    openLinkInWebView("https://about.xiaoxia.com", "关于小夏")
                    true
                }
                else -> false
            }
        }

        popup.show()
    }

    private fun updateConversationList(conversations: List<Conversation>) {
        Log.d("MainActivity", "更新对话列表，数量: ${conversations.size}")
        conversationAdapter.submitList(conversations)
    }

    private fun loadConversation(conversation: Conversation) {
        viewModel.loadConversation(conversation.id)
        drawerLayout.closeDrawer(GravityCompat.START)

        // 添加布局改变监听器，这比单纯的插入监听更准确
        val layoutListener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                // 只要布局发生变化（比如内容填充满了），就执行强制滚动
                messageRecyclerView.removeOnLayoutChangeListener(this)
                messageRecyclerView.post {
                    forceScrollToBottom()
                }
            }
        }
        messageRecyclerView.addOnLayoutChangeListener(layoutListener)
    }

    // 对话历史适配器
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

            if (oldPosition != -1) {
                notifyItemChanged(oldPosition)
            }
            if (selectedPosition != -1) {
                notifyItemChanged(selectedPosition)
            }
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.text_title)
            val summary: TextView = itemView.findViewById(R.id.text_summary)
            val time: TextView = itemView.findViewById(R.id.text_time)
            val deleteBtn: Button = itemView.findViewById(R.id.btn_delete)
            val indicator: View = itemView.findViewById(R.id.indicator_selected)
            val root: RelativeLayout = itemView.findViewById<RelativeLayout>(R.id.root)
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

            // 设置选中状态
            holder.indicator.visibility = if (position == selectedPosition) View.VISIBLE else View.GONE
            holder.root.isSelected = position == selectedPosition

            // 点击事件
            holder.root.setOnClickListener {
                onItemClick(conversation)
                selectConversation(conversation.id)
            }

            // 长按事件
            holder.root.setOnLongClickListener {
                showDeleteButton(holder, conversation)
                true
            }

            // 删除按钮点击事件
            holder.deleteBtn.setOnClickListener {
                deleteConversation(conversation)
                hideDeleteButton(holder)
            }

            // 点击其他地方隐藏删除按钮
            holder.root.setOnTouchListener { v, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    hideDeleteButton(holder)
                }
                false
            }
        }

        override fun getItemCount(): Int = conversations.size
    }

    private fun showDeleteButton(holder: ConversationAdapter.ViewHolder, conversation: Conversation) {
        holder.deleteBtn.visibility = View.VISIBLE
        longPressConversationId = conversation.id

        // 5秒后自动隐藏删除按钮
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
