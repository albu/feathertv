package com.feathertv.launcher.ui

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.feathertv.launcher.R
import com.feathertv.launcher.data.AppInfo
import com.feathertv.launcher.databinding.ItemAppCardBinding

class AppAdapter(
    private val onItemClick: (AppInfo) -> Unit,
    private val onMoveStarted: () -> Unit = {},
    private val onOrderChanged: (List<AppInfo>) -> Unit = {},
    private val onItemFocused: ((AppInfo) -> Unit)? = null
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    private val items = mutableListOf<AppInfo>()
    private var focusStrokeColor: Int = 0
    private var moveStrokeColor: Int = 0

    /** Set by the host so focus can be restored after rebinds. */
    var recyclerView: RecyclerView? = null

    private var moveMode = false
    private var movePosition = -1

    fun isInMoveMode(): Boolean = moveMode

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newItems: List<AppInfo>) {
        moveMode = false
        movePosition = -1
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        if (focusStrokeColor == 0) {
            focusStrokeColor = ContextCompat.getColor(parent.context, R.color.card_stroke_focused)
            moveStrokeColor = 0xFFF59E0B.toInt()
        }
        val binding = ItemAppCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AppViewHolder(binding.root, binding.cardContainer, binding.ivAppIcon, binding.tvAppName)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    private fun enterMoveMode(position: Int, view: View) {
        if (moveMode) {
            cancelMove()
            return
        }
        moveMode = true
        movePosition = position
        // Highlight without rebinding, so the focused tile never loses focus.
        (view.background as? GradientDrawable)?.setStroke(strokeWidthPx(view), moveStrokeColor)
        onMoveStarted()
        view.requestFocus()
        refocus(position)
    }

    fun cancelMove() {
        if (!moveMode) return
        val last = movePosition
        moveMode = false
        movePosition = -1
        notifyItemChanged(last)
        refocus(last)
    }

    private fun strokeWidthPx(view: View): Int =
        (view.context.resources.displayMetrics.density * 3f).toInt()

    /** Move the picked tile to the focused destination and persist. */
    private fun dropAt(target: Int) {
        if (!moveMode) return
        val from = movePosition
        if (from == target) {
            cancelMove()
            return
        }
        val moving = items.removeAt(from)
        items.add(target, moving)
        moveMode = false
        movePosition = -1
        notifyDataSetChanged()
        refocus(target)
        onOrderChanged(items.toList())
    }

    private fun refocus(position: Int) {
        recyclerView?.post {
            recyclerView?.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
    }

    inner class AppViewHolder(
        root: View,
        private val cardContainer: View,
        private val iconView: ImageView,
        private val labelView: TextView
    ) : RecyclerView.ViewHolder(root) {

        private val strokeWidthPx = (root.context.resources.displayMetrics.density * 3f).toInt()

        init {
            cardContainer.setOnFocusChangeListener { view, hasFocus ->
                FocusAnimator.applyFocusEffect(view, hasFocus)
                updateFocusStroke(hasFocus)
                // Match the header buttons: play the system click on focus gain.
                if (hasFocus) {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                        onItemFocused?.invoke(items[pos])
                    }
                }
            }

            cardContainer.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && position < items.size) {
                    if (moveMode) {
                        if (position == movePosition) {
                            cancelMove()
                        } else {
                            dropAt(position)
                        }
                    } else {
                        onItemClick(items[position])
                    }
                }
            }

            cardContainer.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && position < items.size) {
                    enterMoveMode(position, cardContainer)
                    true
                } else {
                    false
                }
            }
        }

        fun bind(app: AppInfo, position: Int) {
            // A rebind may follow a press dip, so restore the base transform.
            cardContainer.scaleX = 1f
            cardContainer.scaleY = 1f

            labelView.text = app.label
            val moving = moveMode && position == movePosition
            cardContainer.background = buildGradient(app.tileColor, cardContainer.hasFocus(), moving)

            if (app.icon != null) {
                iconView.setImageDrawable(app.icon)
            } else {
                iconView.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        }

        private fun updateFocusStroke(hasFocus: Boolean) {
            val gradient = cardContainer.background as? GradientDrawable ?: return
            val moving = moveMode && bindingAdapterPosition == movePosition
            gradient.setStroke(
                if (hasFocus || moving) strokeWidthPx else 0,
                when {
                    moving -> moveStrokeColor
                    hasFocus -> focusStrokeColor
                    else -> 0
                }
            )
        }

        /** Flat, square gradient tile derived from the app's icon color. */
        private fun buildGradient(tileColor: Int?, focused: Boolean, moving: Boolean): GradientDrawable {
            val colors = if (tileColor != null) {
                val top = ColorUtils.blendARGB(tileColor, 0x0A0A0E, 0.45f)
                val bottom = ColorUtils.blendARGB(tileColor, 0x0A0A0E, 0.78f)
                intArrayOf(top, bottom)
            } else {
                intArrayOf(0xFF1A1A24.toInt(), 0xFF14141C.toInt())
            }
            return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                cornerRadius = 0f
                setStroke(
                    if (focused || moving) strokeWidthPx else 0,
                    when {
                        moving -> moveStrokeColor
                        focused -> focusStrokeColor
                        else -> 0
                    }
                )
            }
        }
    }
}
