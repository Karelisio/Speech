package com.karelisio.speech.util

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.karelisio.speech.access.SpeechAccessibilityService

/** Une autorisation (ou un réglage système) nécessaire au fonctionnement de l'app. */
enum class AppPermission(val title: String, val why: String) {
    MICROPHONE("Micro", "Enregistrer ta voix pendant la dictée. Rien ne quitte le téléphone."),
    NOTIFICATIONS("Notifications", "Afficher la notification du service de dictée et la progression des téléchargements."),
    BATTERY("Batterie sans restriction", "Empêcher Android de couper le service de dictée et les téléchargements en arrière-plan."),
    ACCESSIBILITY(
        "Accessibilité",
        "Afficher la bulle quand un champ de texte est actif et y insérer la dictée. " +
            "Dans la liste, choisis Speech puis active-le. Si l'interrupteur est grisé (app installée " +
            "hors Play Store) : bouton « Infos de l'appli » → ⋮ en haut à droite → « Autoriser les " +
            "paramètres restreints », puis réessaie.",
    ),
    MAGICOS(
        "MagicOS : lancement et arrière-plan",
        "Paramètres → Batterie → Lancement d'applications → Speech : désactive « Gérer automatiquement », " +
            "puis active Lancement automatique, Lancement secondaire et Exécution en arrière-plan.",
    ),
}

object AppPermissions {
    val isHonorOrHuawei: Boolean
        get() = Build.MANUFACTURER.lowercase().let { it.contains("honor") || it.contains("huawei") }

    /** Étapes pertinentes sur cet appareil, dans l'ordre de l'assistant. */
    fun steps(): List<AppPermission> = AppPermission.entries.filter {
        when (it) {
            AppPermission.NOTIFICATIONS -> Build.VERSION.SDK_INT >= 33
            AppPermission.MAGICOS -> isHonorOrHuawei
            else -> true
        }
    }

    /** Les réglages constructeur ne sont pas vérifiables : l'utilisateur confirme lui-même. */
    fun isManual(p: AppPermission) = p == AppPermission.MAGICOS

    fun isGranted(context: Context, p: AppPermission, prefs: Prefs): Boolean = when (p) {
        AppPermission.MICROPHONE -> granted(context, Manifest.permission.RECORD_AUDIO)
        AppPermission.NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
        AppPermission.BATTERY -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        AppPermission.ACCESSIBILITY -> SpeechAccessibilityService.isEnabled(context)
        AppPermission.MAGICOS -> prefs.magicOsConfirmed
    }

    fun allGranted(context: Context, prefs: Prefs) = steps().all { isGranted(context, it, prefs) }

    private fun granted(context: Context, perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun openAppDetails(context: Context) {
        context.startActivity(appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Ouvre l'écran système correspondant (hors permissions runtime, gérées par l'activité). */
    fun openSettings(context: Context, p: AppPermission) {
        val pkg = Uri.parse("package:${context.packageName}")
        val candidates = when (p) {
            AppPermission.BATTERY -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            AppPermission.ACCESSIBILITY -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            AppPermission.MAGICOS -> listOf(
                Intent().setComponent(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            )
            else -> emptyList()
        } + appDetails(context)
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
    }
}
