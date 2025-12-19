package com.derbi.xiaoxia.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.derbi.xiaoxia.R

data class Suggestion(val title: String, val content: String)

class WelcomeSuggestionAdapter(
    private val suggestions: List<Suggestion>,
    private val onSuggestionClick: (String) -> Unit
) : RecyclerView.Adapter<WelcomeSuggestionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.text_title)
        val content: TextView = view.findViewById(R.id.text_content)
        val card: View = view.findViewById(R.id.card_suggestion)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_welcome_suggestion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = suggestions[position]
        holder.title.text = item.title
        holder.content.text = item.content

        holder.card.setOnClickListener {
            onSuggestionClick(item.content) // 点击时返回完整的文本
        }
    }

    override fun getItemCount() = suggestions.size
}
