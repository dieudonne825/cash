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

    /**
     * Android 10+ uses RoleManager. Older versions use ACTION_CHANGE_DEFAULT.
     */
    fun requestDefaultSmsRole(activity: Activity, onReturned: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS) && !roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                activity.startActivityForResult(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS),
                    1101
                )
                onReturned()
                return
            }
        }

        // Fallback (also works on many devices)
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, activity.packageName)
        }
        activity.startActivity(intent)
        onReturned()
    }
}
