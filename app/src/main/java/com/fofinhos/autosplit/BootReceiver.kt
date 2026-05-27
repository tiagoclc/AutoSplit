package com.fofinhos.autosplit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action || Intent.ACTION_LOCKED_BOOT_COMPLETED == intent.action) {
            val prefs = context.getSharedPreferences("autosplit_prefs", Context.MODE_PRIVATE)
            val autoStartEnabled = prefs.getBoolean("boot_auto_start", true)
            
            if (autoStartEnabled) {
                val i = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("AUTO_START", true)
                }
                context.startActivity(i)
            }
        }
    }
}