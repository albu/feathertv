package com.feathertv.launcher.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.feathertv.launcher.R
import com.feathertv.launcher.data.AppInfo
import com.feathertv.launcher.databinding.DialogAppListBinding
import com.feathertv.launcher.databinding.ItemAppListRowBinding

/**
 * Right-docked app list (same panel style as launcher settings). Picking an app
 * opens its options panel, so apps can be managed without long-press.
 */
class AppListDialog(
    context: Context,
    private val apps: List<AppInfo>,
    private val onManageApp: (AppInfo) -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogAppListBinding

    companion object {
        private const val PANEL_WIDTH_DP = 360f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val panelWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, PANEL_WIDTH_DP, context.resources.displayMetrics
        ).toInt()
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.END or Gravity.TOP)
            setLayout(panelWidthPx, WindowManager.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
            setWindowAnimations(R.style.SettingsPanelAnimation)
        }

        setCanceledOnTouchOutside(true)

        if (apps.isEmpty()) {
            binding.rvApps.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
        } else {
            binding.rvApps.layoutManager = LinearLayoutManager(context)
            binding.rvApps.adapter = AppListAdapter(apps)
        }
    }

    private inner class AppListAdapter(private val items: List<AppInfo>) :
        RecyclerView.Adapter<AppListAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = ItemAppListRowBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(private val binding: ItemAppListRowBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(app: AppInfo) {
                binding.tvAppName.text = app.label
                if (app.icon != null) {
                    binding.ivAppIcon.setImageDrawable(app.icon)
                } else {
                    binding.ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
                binding.root.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION && position < items.size) {
                        dismiss()
                        onManageApp(items[position])
                    }
                }
            }
        }
    }
}
