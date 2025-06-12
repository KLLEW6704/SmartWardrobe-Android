package com.example.smartwardrobe.data

import android.content.Context
import android.util.Log
import com.example.smartwardrobe.ui.RecommendActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.content.edit

object ClothingManager {
    fun saveItems(context: Context, items: List<RecommendActivity.ClothingItem>) {
        try {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { putString(Constants.KEY_ITEMS, Gson().toJson(items)) }
        } catch (e: Exception) {
            Log.e("ClothingManager", "Error saving items", e)
        }
    }

    fun loadItems(context: Context): List<RecommendActivity.ClothingItem> {
        try {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val savedItems = prefs.getString(Constants.KEY_ITEMS, null)

            return if (!savedItems.isNullOrEmpty()) {
                val type = object : TypeToken<List<RecommendActivity.ClothingItem>>() {}.type
                Gson().fromJson(savedItems, type) ?: emptyList()
            } else {
                // 从默认文件加载
                val jsonString = context.assets.open(Constants.DEFAULT_JSON).bufferedReader().use { it.readText() }
                val type = object : TypeToken<List<RecommendActivity.ClothingItem>>() {}.type
                val items: List<RecommendActivity.ClothingItem> = Gson().fromJson(jsonString, type) ?: emptyList()

                // 保存默认数据
                if (items.isNotEmpty()) {
                    saveItems(context, items)
                }

                items
            }
        } catch (e: Exception) {
            Log.e("ClothingManager", "Error loading items", e)
            return emptyList()
        }
    }
}