package com.pettycash.cashsms.sms

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony

object DefaultSmsRole {
    fun isDefaultSmsApp(context: Context): Boolean {
        val pkg = context.packageName
        return Telephony.Sms.getDefaultSmsPackage(context) == pkg
    }

    fun getDefaultSmsIntent(context: Context): Intent? {
        if (isDefaultSmsApp(context)) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS) && !roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
            }
        }

        return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }
    }
}
