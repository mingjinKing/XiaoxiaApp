package com.derbi.xiaoxia.adapters

import android.graphics.Color
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.derbi.xiaoxia.R
import com.derbi.xiaoxia.models.Message
import com.derbi.xiaoxia.utils.TimeFormatter
import android.text.Spanned
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

class MessageAdapter(
    private val onLinkClick: (String) -> Unit
) : ListAdapter<Message, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_AI = 2
        private const val VIEW_TYPE_LOADING = 3
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is Message.UserMessage -> VIEW_TYPE_USER
            is Message.AIMessage -> VIEW_TYPE_AI
            Message.LoadingMessage -> VIEW_TYPE_LOADING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> UserMessageViewHolder(
                inflater.inflate(R.layout.item_message_user, parent, false)
            )
            VIEW_TYPE_AI -> {
                val markwon = Markwon.builder(parent.context)
                    .usePlugin(TablePlugin.create(parent.context))
                    .usePlugin(LinkifyPlugin.create())
                    .build()

                AIMessageViewHolder(
                    inflater.inflate(R.layout.item_message_ai, parent, false),
                    onLinkClick,
                    markwon
                )
            }
            VIEW_TYPE_LOADING -> LoadingMessageViewHolder(
                inflater.inflate(R.layout.item_message_loading, parent, false)
            )
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Message.UserMessage -> (holder as UserMessageViewHolder).bind(item)
            is Message.AIMessage -> (holder as AIMessageViewHolder).bind(item)
            Message.LoadingMessage -> (holder as LoadingMessageViewHolder).bind()
        }
    }

    inner class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textMessage: TextView = view.findViewById(R.id.text_message)
        private val textTimestamp: TextView = view.findViewById(R.id.text_timestamp)

        fun bind(message: Message.UserMessage) {
            textMessage.text = message.content
            textTimestamp.text = TimeFormatter.formatRelativeTime(message.timestamp)

            // 处理消息中的链接
            val spannable = SpannableString(message.content)
            Linkify.addLinks(spannable, Linkify.WEB_URLS)

            // 替换默认的URLSpan，使用自定义的点击处理
            val urlSpans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
            for (urlSpan in urlSpans) {
                val start = spannable.getSpanStart(urlSpan)
                val end = spannable.getSpanEnd(urlSpan)
                val url = urlSpan.url

                spannable.removeSpan(urlSpan)
                spannable.setSpan(
                    object : URLSpan(url) {
                        override fun onClick(widget: View) {
                            onLinkClick(url)
                        }
                    },
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            textMessage.text = spannable
            textMessage.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    inner class AIMessageViewHolder(
        view: View,
        private val onLinkClick: (String) -> Unit,
        private val markwon: Markwon
    ) : RecyclerView.ViewHolder(view) {
        private val textMessage: TextView = view.findViewById(R.id.text_message)
        private val textTimestamp: TextView = view.findViewById(R.id.text_timestamp)
        private val textReasoning: TextView = view.findViewById(R.id.text_reasoning)
        private val layoutReasoning: View = view.findViewById(R.id.layout_reasoning)
        private val layoutReasoningToggle: View = view.findViewById(R.id.layout_reasoning_toggle)
        private val layoutReasoningContent: View = view.findViewById(R.id.layout_reasoning_content)
        private val layoutDisclaimer: View = view.findViewById(R.id.layout_disclaimer)
        private val chevronIcon: ImageView = itemView.findViewById<ImageView>(R.id.imageView)

        // 添加状态缓存
        private var currentDisclaimerVisibility = View.GONE
        private var currentMessageId: String? = null

        fun bind(message: Message.AIMessage) {
            // 如果消息ID相同且免责声明状态未变，避免重复设置
            val isSameMessage = currentMessageId == message.id
            val isTableContent = message.content.contains("|") && message.content.contains("----")

            textTimestamp.text = TimeFormatter.formatRelativeTime(message.timestamp)

            // 使用 Markwon 渲染 Markdown
            markwon.setMarkdown(textMessage, message.content)
            textMessage.movementMethod = LinkMovementMethod.getInstance()

            // 处理思考内容
            if (message.reasoningContent.isNotEmpty()) {
                layoutReasoning.visibility = View.VISIBLE
                markwon.setMarkdown(textReasoning, message.reasoningContent)
                textReasoning.movementMethod = LinkMovementMethod.getInstance()

                if (message.showReasoning) {
                    layoutReasoningContent.visibility = View.VISIBLE
                    chevronIcon.rotation = 0f
                    layoutReasoningToggle.findViewById<TextView>(R.id.textView).text = "收起思考过程"
                } else {
                    layoutReasoningContent.visibility = View.GONE
                    chevronIcon.rotation = -90f
                    layoutReasoningToggle.findViewById<TextView>(R.id.textView).text = "查看深度思考"
                }

                // 思考区域点击切换（只在非流式接收时添加）
                if (!message.isReceivingReasoning) {
                    layoutReasoningToggle.setOnClickListener {
                        val isExpanded = layoutReasoningContent.visibility == View.VISIBLE
                        layoutReasoningContent.visibility = if (isExpanded) View.GONE else View.VISIBLE
                        chevronIcon.rotation = if (isExpanded) -90f else 0f
                        layoutReasoningToggle.findViewById<TextView>(R.id.textView).text =
                            if (isExpanded) "查看深度思考" else "收起思考过程"
                    }
                } else {
                    layoutReasoningToggle.setOnClickListener(null)
                }
            } else {
                layoutReasoning.visibility = View.GONE
            }

            // 关键修改：免责声明的处理
            if (!isSameMessage || layoutDisclaimer.visibility != (if (message.showDisclaimer) View.VISIBLE else View.GONE)) {
                layoutDisclaimer.visibility = if (message.showDisclaimer) View.VISIBLE else View.GONE
                currentDisclaimerVisibility = layoutDisclaimer.visibility

                // 如果是流式输出，添加防抖处理
                if (message.isReceiving) {
                    // 添加硬件加速层，防止绘制闪烁
                    layoutDisclaimer.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                } else {
                    layoutDisclaimer.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            }

            currentMessageId = message.id
        }
    }

    inner class LoadingMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind() {
            // 加载状态不需要额外处理
        }
    }
}

class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
    override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
        return when {
            oldItem is Message.UserMessage && newItem is Message.UserMessage ->
                oldItem.id == newItem.id
            oldItem is Message.AIMessage && newItem is Message.AIMessage ->
                oldItem.id == newItem.id
            oldItem is Message.LoadingMessage && newItem is Message.LoadingMessage ->
                true
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
        return when {
            oldItem is Message.UserMessage && newItem is Message.UserMessage ->
                oldItem.content == newItem.content
            oldItem is Message.AIMessage && newItem is Message.AIMessage ->
                oldItem.content == newItem.content &&
                        oldItem.reasoningContent == newItem.reasoningContent &&
                        oldItem.showReasoning == newItem.showReasoning &&
                        oldItem.showDisclaimer == newItem.showDisclaimer
            oldItem is Message.LoadingMessage && newItem is Message.LoadingMessage ->
                true
            else -> false
        }
    }
}
