package com.feathertv.launcher.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import com.feathertv.launcher.R
import com.feathertv.launcher.data.AppInfo
import com.feathertv.launcher.data.AppPreferences
import com.feathertv.launcher.databinding.DialogAppOptionsBinding

class AppOptionDialog(
    context: Context,
    private val appInfo: AppInfo,
    private val onAppUpdated: () -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogAppOptionsBinding
    private val preferences = AppPreferences(context)

    companion object {
        private const val PANEL_WIDTH_DP = 360f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogAppOptionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Right-docked side panel, matching the launcher settings style.
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

        setupView()
        setupListeners()
    }

    private fun setupView() {
        binding.dialogAppName.text = appInfo.label
        binding.dialogAppPackage.text = appInfo.packageName
        if (appInfo.icon != null) {
            binding.dialogAppIcon.setImageDrawable(appInfo.icon)
        } else {
            binding.dialogAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        // Setup Hide state
        val isHidden = preferences.isHidden(appInfo.packageName)
        binding.tvHideText.text = if (isHidden) {
            context.getString(R.string.dialog_unhide)
        } else {
            context.getString(R.string.dialog_hide)
        }

    }

    private fun setupListeners() {
        binding.btnToggleHide.setOnClickListener {
            val isHidden = preferences.isHidden(appInfo.packageName)
            preferences.setHidden(appInfo.packageName, !isHidden)
            onAppUpdated()
            dismiss()
        }

        binding.btnAppInfo.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", appInfo.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            dismiss()
        }

        binding.btnUninstall.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.fromParts("package", appInfo.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            dismiss()
        }
    }
}
