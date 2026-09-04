package com.feathertv.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class AppRepository(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val preferences = AppPreferences(context)

    suspend fun getInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val appMap = mutableMapOf<String, AppInfo>()
        val newTileColors = mutableMapOf<String, Int>()
        val selfPackage = context.packageName

        // 1. Query Leanback (Android TV) Launcher Apps
        val leanbackIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        val leanbackApps: List<ResolveInfo> = packageManager.queryIntentActivities(
            leanbackIntent,
            PackageManager.MATCH_ALL
        )

        for (info in leanbackApps) {
            val pkg = info.activityInfo.packageName
            if (pkg == selfPackage) continue

            val label = info.loadLabel(packageManager)?.toString() ?: pkg
            val icon = try {
                info.loadIcon(packageManager)
            } catch (e: Exception) {
                null
            }

            val tileColor = preferences.getTileColor(pkg)
                ?: IconColorExtractor.extract(icon).also { color ->
                    if (color != null) newTileColors[pkg] = color
                }
            val isHidden = preferences.isHidden(pkg)

            appMap[pkg] = AppInfo(
                packageName = pkg,
                activityName = info.activityInfo.name,
                label = label,
                icon = icon,
                tileColor = tileColor,
                isHidden = isHidden
            )
        }

        // 2. Query Standard Launcher Apps (Sideloaded apps, mobile apps, utilities)
        val standardIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val standardApps: List<ResolveInfo> = packageManager.queryIntentActivities(
            standardIntent,
            PackageManager.MATCH_ALL
        )

        for (info in standardApps) {
            val pkg = info.activityInfo.packageName
            if (pkg == selfPackage || appMap.containsKey(pkg)) continue

            val label = info.loadLabel(packageManager)?.toString() ?: pkg
            val icon = try {
                info.loadIcon(packageManager)
            } catch (e: Exception) {
                null
            }

            val tileColor = preferences.getTileColor(pkg)
                ?: IconColorExtractor.extract(icon).also { color ->
                    if (color != null) newTileColors[pkg] = color
                }
            val isHidden = preferences.isHidden(pkg)

            appMap[pkg] = AppInfo(
                packageName = pkg,
                activityName = info.activityInfo.name,
                label = label,
                icon = icon,
                tileColor = tileColor,
                isHidden = isHidden
            )
        }

        preferences.setTileColors(newTileColors)

        val showHidden = preferences.showHidden

        val visible = appMap.values
            .filter { showHidden || !it.isHidden }

        // User-defined order when present; otherwise alphabetical.
        val orderIndex = preferences.getAppOrder()
            .withIndex()
            .associate { it.value to it.index }
        val sorted = if (orderIndex.isEmpty()) {
            visible.sortedBy { it.label.lowercase(Locale.getDefault()) }
        } else {
            visible.sortedWith(
                compareBy<AppInfo> { orderIndex[it.packageName] ?: Int.MAX_VALUE }
                    .thenBy { it.label.lowercase(Locale.getDefault()) }
            )
        }

        return@withContext sorted
    }

    fun launchApp(appInfo: AppInfo): Boolean {
        return try {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(appInfo.packageName)
                ?: packageManager.getLaunchIntentForPackage(appInfo.packageName)
                ?: Intent().apply {
                    setClassName(appInfo.packageName, appInfo.activityName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isPackageInstalled(packageName: String): Boolean {
        return try {
            // TV apps often declare only LEANBACK_LAUNCHER (no LAUNCHER), so the
            // plain launch intent can be null even though the app is installed.
            packageManager.getLeanbackLaunchIntentForPackage(packageName) != null ||
                packageManager.getLaunchIntentForPackage(packageName) != null
        } catch (e: Exception) {
            false
        }
    }

    /** Launch a provider app (or any installed app) by package name. */
    fun launchPackage(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
