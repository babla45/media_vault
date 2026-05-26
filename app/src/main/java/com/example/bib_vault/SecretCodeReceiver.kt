package com.example.bib_vault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            launchApp(context)
        } else if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
            val phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
            if (phoneNumber == "*5643#") {
                resultData = null // Cancel the call broadcast
                launchApp(context)
            }
        }
    }

    private fun launchApp(context: Context) {
        val i = Intent(context, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        context.startActivity(i)
    }
}
