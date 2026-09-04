package com.feathertv.launcher.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.feathertv.launcher.R
import com.feathertv.launcher.data.PosterLoader
import com.feathertv.launcher.data.SearchResult
import com.feathertv.launcher.databinding.ItemSearchFooterBinding
import com.feathertv.launcher.databinding.ItemSearchResultBinding
import java.util.Locale

/** Search results list: poster, title, year · rating, plus full-span load more footer. */
class SearchAdapter(
    private val posterLoader: PosterLoader,
    private val onItemClick: (SearchResult) -> Unit,
    private val onLoadMoreClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_ITEM = 0
        const val TYPE_FOOTER = 1
    }

    private val items = mutableListOf<SearchResult>()
    private var hasMore = false
    private var isLoadingMore = false

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newItems: List<SearchResult>, canLoadMore: Boolean = false, loadingMore: Boolean = false) {
        items.clear()
        items.addAll(newItems)
        hasMore = canLoadMore
        isLoadingMore = loadingMore
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setLoadingMoreState(loading: Boolean) {
        isLoadingMore = loading
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == items.size) TYPE_FOOTER else TYPE_ITEM
    }

    override fun getItemCount(): Int = items.size + (if (hasMore) 1 else 0)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOOTER) {
            val binding = ItemSearchFooterBinding.inflate(inflater, parent, false)
            FooterViewHolder(binding)
        } else {
            val binding = ItemSearchResultBinding.inflate(inflater, parent, false)
            ResultViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ResultViewHolder) {
            holder.bind(items[position])
        } else if (holder is FooterViewHolder) {
            holder.bind(isLoadingMore)
        }
    }

    inner class FooterViewHolder(private val binding: ItemSearchFooterBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.btnLoadMoreFooter.setOnClickListener {
                onLoadMoreClick()
            }
        }

        fun bind(loading: Boolean) {
            binding.tvLoadMoreFooter.text = if (loading) {
                binding.root.context.getString(R.string.search_loading_more)
            } else {
                binding.root.context.getString(R.string.search_load_more)
            }
        }
    }

    inner class ResultViewHolder(private val binding: ItemSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && position < items.size) {
                    onItemClick(items[position])
                }
            }
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                FocusAnimator.applyFocusEffect(view, hasFocus)
            }
        }

        fun bind(result: SearchResult) {
            binding.tvTitle.text = result.title
            val typeLabel = if (result.mediaType == "tv") "TV" else "Movie"
            val parts = buildList {
                if (result.year.isNotBlank()) add(result.year)
                add(typeLabel)
            }
            binding.tvMeta.text = parts.joinToString(" · ")
            if (result.rating > 0) {
                binding.tvRating.text = String.format(Locale.US, "★ %.1f", result.rating)
                binding.tvRating.visibility = View.VISIBLE
            } else {
                binding.tvRating.visibility = View.GONE
            }
            posterLoader.load(result.posterUrl, binding.ivPoster)
        }
    }
}
