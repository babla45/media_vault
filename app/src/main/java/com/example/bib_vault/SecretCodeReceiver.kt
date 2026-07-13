package com.example.bib_vault

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TelephonyManager.ACTION_SECRET_CODE,
            "android.provider.Telephony.SECRET_CODE" -> {
                if (isOurSecretCode(intent.data)) {
                    launchApp(context)
                }
            }
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                // Legacy fallback for pre-Android 10 devices only; number is null on API 29+.
                val phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: return
                if (normalizeDialString(phoneNumber) == LEGACY_DIAL_CODE) {
                    resultData = null
                    launchApp(context)
                }
            }
        }
    }

    private fun isOurSecretCode(uri: Uri?): Boolean {
        return uri?.scheme == "android_secret_code" && uri.host == SECRET_CODE_HOST
    }

    private fun normalizeDialString(number: String): String {
        return number.replace(Regex("\\s"), "")
    }

    private fun launchApp(context: Context) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            pendingIntent.send()
        } catch (_: PendingIntent.CanceledException) {
            context.startActivity(launchIntent)
        }
    }

    companion object {
        const val SECRET_CODE_HOST = "5643"
        const val DIAL_CODE = "*#*#5643#*#*"
        private const val LEGACY_DIAL_CODE = "*5643#"
    }
}
