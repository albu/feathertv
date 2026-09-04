package com.feathertv.launcher.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.feathertv.launcher.R
import com.feathertv.launcher.data.PosterLoader
import com.feathertv.launcher.data.ProviderAction
import com.feathertv.launcher.data.SearchDetails
import com.feathertv.launcher.data.SearchResult
import com.feathertv.launcher.databinding.DialogSearchDetailBinding
import java.util.Locale

/**
 * Full-screen cinematic detail window for a selected search result.
 * Displays high-res backdrop, large poster card, metadata badges, overview,
 * and unified action buttons for installed providers.
 */
class SearchDetailDialog(
    context: Context,
    private val result: SearchResult,
    private val posterLoader: PosterLoader,
    private val onOpenProvider: (String) -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private lateinit var binding: DialogSearchDetailBinding
    private var providerRowsAdded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogSearchDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setDimAmount(0.6f)
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        setCanceledOnTouchOutside(true)

        // Populate initial information immediately from search result
        binding.tvDetailTitle.text = result.title
        if (result.year.isNotBlank()) {
            binding.tvDetailYear.text = result.year
            binding.tvDetailYear.visibility = View.VISIBLE
        } else {
            binding.tvDetailYear.visibility = View.GONE
        }

        if (result.rating > 0) {
            binding.tvDetailRating.text = String.format(Locale.US, "★ %.1f", result.rating)
            binding.tvDetailRating.visibility = View.VISIBLE
        } else {
            binding.tvDetailRating.visibility = View.GONE
        }

        binding.tvDetailRuntime.visibility = View.GONE
        binding.tvDetailGenres.text = if (result.mediaType == "tv") "TV Show" else "Movie"

        posterLoader.load(result.posterUrl, binding.ivPoster)
        if (!result.backdropUrl.isNullOrBlank()) {
            posterLoader.load(result.backdropUrl, binding.ivBackdrop)
        }
    }

    /** Called once full details + providers have been fetched asynchronously. */
    fun populate(
        details: SearchDetails,
        actions: List<ProviderAction>
    ) {
        if (details.tagline.isNotBlank()) {
            binding.tvDetailTagline.text = details.tagline
            binding.tvDetailTagline.visibility = View.VISIBLE
        }

        if (details.genres.isNotEmpty()) {
            binding.tvDetailGenres.text = details.genres.take(3).joinToString(" · ")
        }

        if (details.runtimeMinutes > 0) {
            val runtimeStr = if (result.mediaType == "tv" && details.runtimeMinutes < 30) {
                "${details.runtimeMinutes} Season" + (if (details.runtimeMinutes > 1) "s" else "")
            } else {
                formatRuntime(details.runtimeMinutes)
            }
            binding.tvDetailRuntime.text = runtimeStr
            binding.tvDetailRuntime.visibility = View.VISIBLE
        }

        binding.tvDetailOverview.text = details.overview.ifBlank {
            context.getString(R.string.search_no_overview)
        }

        // Upgrade the dialog poster to the sharper details image (the grid only
        // carries a light w185 copy).
        if (!details.posterUrl.isNullOrBlank() && details.posterUrl != result.posterUrl) {
            posterLoader.load(details.posterUrl, binding.ivPoster)
        }
        if (!details.backdropUrl.isNullOrBlank() && details.backdropUrl != result.backdropUrl) {
            posterLoader.load(details.backdropUrl, binding.ivBackdrop)
        }

        if (actions.isEmpty()) {
            binding.tvActionsHeader.visibility = View.GONE
            binding.tvDetailStatus.text = context.getString(R.string.search_not_available)
            binding.tvDetailStatus.visibility = View.VISIBLE
            return
        }

        binding.tvDetailStatus.visibility = View.GONE
        binding.tvActionsHeader.text = context.getString(R.string.search_watch_options).uppercase(Locale.US)
        binding.tvActionsHeader.visibility = View.VISIBLE

        if (providerRowsAdded) return

        var firstFocusedView: View? = null

        actions.forEach { action ->
            val iconRes = if (action.tagText == context.getString(R.string.search_tag_search)) {
                R.drawable.ic_search
            } else {
                R.drawable.ic_play
            }
            val row = buildActionButton(
                iconRes = iconRes,
                title = action.title,
                tagText = action.tagText,
                isPrimary = action.isPrimary,
                container = binding.detailProviderRows
            ) {
                dismiss()
                onOpenProvider(action.packageName)
            }
            if (firstFocusedView == null) firstFocusedView = row
        }

        providerRowsAdded = true
        firstFocusedView?.let { v ->
            v.post { v.requestFocus() }
        }
    }

    private fun buildActionButton(
        iconRes: Int,
        title: String,
        tagText: String,
        isPrimary: Boolean,
        container: LinearLayout,
        onClick: () -> Unit
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minimumHeight = dp(46)
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(context, R.drawable.bg_detail_action_btn)
            setOnClickListener { onClick() }
        }

        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(ContextCompat.getColor(context, R.color.text_primary))
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
            setMargins(0, 0, dp(12), 0)
        })

        val titleView = TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        row.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val tagView = TextView(context).apply {
            text = tagText
            setTextColor(if (isPrimary) ContextCompat.getColor(context, R.color.card_stroke_focused) else ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ContextCompat.getDrawable(context, R.drawable.bg_badge)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        row.addView(tagView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val layoutParams = LinearLayout.LayoutParams(
            dp(460),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(3), 0, dp(3))
        }
        container.addView(row, layoutParams)
        return row
    }

    private fun formatRuntime(totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
