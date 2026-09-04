package com.feathertv.launcher

import android.content.Intent
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.feathertv.launcher.data.AppInfo
import com.feathertv.launcher.data.AppPreferences
import com.feathertv.launcher.data.AppRepository
import com.feathertv.launcher.data.GradientBackground
import com.feathertv.launcher.databinding.ActivityMainBinding
import com.feathertv.launcher.ui.AppAdapter
import com.feathertv.launcher.ui.AppOptionDialog
import com.feathertv.launcher.ui.FocusAnimator
import com.feathertv.launcher.ui.SettingsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AppRepository
    private lateinit var preferences: AppPreferences

    private lateinit var allAppsAdapter: AppAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    /** Latest visible app list, passed to settings so apps can be managed there. */
    private var allApps: List<AppInfo> = emptyList()

    companion object {
        var instance: MainActivity? = null
            private set
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateWifiIcon()
        override fun onLost(network: Network) = updateWifiIcon()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            updateWifiIcon()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppRepository(this)
        preferences = AppPreferences(this)

        setupRecyclerViews()
        setupHeaderButtons()
        applyBackground()
    }

    override fun onResume() {
        super.onResume()
        reloadApps()
        LauncherApp.ledController.onLauncherResumed()
    }

    override fun onPause() {
        super.onPause()
        LauncherApp.ledController.onLauncherPaused()
    }

    override fun onStart() {
        super.onStart()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
        } catch (e: Exception) {
            // Callback registration can fail on some OEM builds; icon still updates on start.
        }
        updateWifiIcon()
    }

    override fun onStop() {
        super.onStop()
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignore; nothing to unregister.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    private fun setupRecyclerViews() {
        // All Apps Adapter & Grid RecyclerView
        allAppsAdapter = AppAdapter(
            onItemClick = { app -> launchApp(app) },
            onMoveStarted = {
                Toast.makeText(
                    this,
                    R.string.move_hint,
                    Toast.LENGTH_SHORT
                ).show()
            },
            onOrderChanged = { order ->
                preferences.setAppOrder(order.map { it.packageName })
            },
            onItemFocused = { app ->
                LauncherApp.ledController.onAppFocused(app)
            }
        )
        val columns = preferences.gridColumns
        gridLayoutManager = GridLayoutManager(this, columns)
        binding.rvAllApps.apply {
            layoutManager = gridLayoutManager
            adapter = allAppsAdapter
            allAppsAdapter.recyclerView = this
            setHasFixedSize(true)
        }
    }

    private fun setupHeaderButtons() {
        binding.btnWifi.setOnClickListener {
            openSystemSettings(Settings.ACTION_WIFI_SETTINGS)
        }

        binding.btnSystemSettings.setOnClickListener {
            openSystemSettings(Settings.ACTION_SETTINGS)
        }

        binding.btnSettings.setOnClickListener {
            val dialog = SettingsDialog(
                this,
                onSettingsChanged = {
                    // When settings change (e.g. columns, show hidden)
                    gridLayoutManager.spanCount = preferences.gridColumns
                    reloadApps()
                },
                apps = allApps,
                onManageApp = { app -> showAppOptions(app) }
            )
            dialog.show()
        }

        binding.btnSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

    }

    private fun openSystemSettings(action: String) {
        try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyBackground() {
        lifecycleScope.launch {
            val background = withContext(Dispatchers.IO) {
                GradientBackground.generate(
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels
                )
            }
            binding.root.background = BitmapDrawable(resources, background)
        }
    }

    /** Reflects Wi-Fi state in the header icon: normal glyph when connected,
     *  slash glyph when Wi-Fi is not the active network. */
    private fun updateWifiIcon() {
        val connected = isWifiConnected()
        binding.ivWifiIcon.setImageResource(if (connected) R.drawable.ic_wifi else R.drawable.ic_wifi_off)
        binding.ivWifiIcon.contentDescription = getString(
            if (connected) R.string.wifi_connected else R.string.wifi_disconnected
        )
    }

    @Suppress("DEPRECATION")
    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
        return cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
    }

    fun reloadApps() {
        lifecycleScope.launch {
            val apps = repository.getInstalledApps()
            allApps = apps

            // Update All Apps section
            if (apps.isNotEmpty()) {
                binding.rvAllApps.visibility = View.VISIBLE
                binding.tvEmptyState.visibility = View.GONE
                allAppsAdapter.submitList(apps)
                focusFirstApp()
            } else {
                binding.rvAllApps.visibility = View.GONE
                binding.tvEmptyState.visibility = View.VISIBLE
            }
        }
    }

    /** Default cursor position is always the first app, never the header. */
    private fun focusFirstApp() {
        binding.rvAllApps.post {
            val holder = binding.rvAllApps.findViewHolderForAdapterPosition(0)
            if (holder != null) {
                holder.itemView.requestFocus()
            }
        }
    }

    private fun launchApp(app: AppInfo) {
        val success = repository.launchApp(app)
        if (!success) {
            Toast.makeText(this, "Failed to launch ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppOptions(app: AppInfo) {
        val dialog = AppOptionDialog(this, app) {
            reloadApps()
        }
        dialog.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_SEARCH ||
                keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
                keyCode == KeyEvent.KEYCODE_ASSIST ||
                keyCode == KeyEvent.KEYCODE_MEDIA_RECORD ||
                keyCode == KeyEvent.KEYCODE_DVR
            ) {
                startActivity(Intent(this, SearchActivity::class.java))
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // The remote's hamburger / Settings button should open TV settings.
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            openSystemSettings(Settings.ACTION_SETTINGS)
            return true
        }

        // Back cancels an in-progress tile move.
        if (keyCode == KeyEvent.KEYCODE_BACK && allAppsAdapter.isInMoveMode()) {
            allAppsAdapter.cancelMove()
            return true
        }

        // Grid wrap navigation: LEFT/RIGHT/DOWN cycle at the edges; UP from the
        // top row goes to the header (settings). RIGHT never escapes to header.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        ) {
            val focused = currentFocus
            // Only treat this as grid navigation when the focused view is a
            // direct grid tile; anything else (header buttons, etc.) is left to
            // the default focus handling. getChildAdapterPosition() would throw
            // on a non-RecyclerView child.
            val position = if (focused != null && focused.parent === binding.rvAllApps) {
                binding.rvAllApps.getChildAdapterPosition(focused)
            } else {
                RecyclerView.NO_POSITION
            }
            if (position != RecyclerView.NO_POSITION) {
                val target = gridNeighbor(
                    position,
                    keyCode,
                    gridLayoutManager.spanCount,
                    allAppsAdapter.itemCount
                )
                if (target != null && target != position) {
                    focusGridPosition(target)
                    return true
                }
                // UP from the top row (or no move): let default focus handling go
                // to the header.
                return super.onKeyDown(keyCode, event)
            }
        }

        // Prevent launcher from exiting on back press
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Scroll to top / first item if back is pressed
            binding.scrollView.smoothScrollTo(0, 0)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Next position with edge wrapping; null for "leave the grid" (UP from top). */
    private fun gridNeighbor(position: Int, keyCode: Int, columns: Int, total: Int): Int? {
        val c = columns.coerceAtLeast(1)
        val rowStart = position / c * c
        val rowCount = minOf(c, total - rowStart)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (position % c > 0) position - 1 else position + (rowCount - 1)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (position % c < rowCount - 1) position + 1 else position - (position % c)
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (position - c >= 0) position - c else null
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (position + c < total) position + c else position % c
            }
            else -> null
        }
    }

    private fun focusGridPosition(position: Int) {
        binding.rvAllApps.scrollToPosition(position)
        binding.rvAllApps.post {
            val holder = binding.rvAllApps.findViewHolderForAdapterPosition(position)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else {
                binding.rvAllApps.post {
                    binding.rvAllApps.findViewHolderForAdapterPosition(position)
                        ?.itemView?.requestFocus()
                }
            }
        }
    }
}
