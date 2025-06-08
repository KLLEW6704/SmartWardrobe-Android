package com.example.smartwardrobe.data

import android.content.Context
import android.content.SharedPreferences

data class UserInfo(var gender: String, var comfort: String)

class UserPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    fun saveUserInfo(userInfo: UserInfo) {
        prefs.edit().apply {
            putString("gender", userInfo.gender)
            putString("comfort", userInfo.comfort)
            apply()
        }
    }

    fun getUserInfo(): UserInfo {
        val gender = prefs.getString("gender", "") ?: ""
        val comfort = prefs.getString("comfort", "") ?: ""
        return UserInfo(gender, comfort)
    }

    fun isUserSet(): Boolean {
        return prefs.contains("gender") && prefs.contains("comfort")
    }
}
