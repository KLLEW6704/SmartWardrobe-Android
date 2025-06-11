package com.example.smartwardrobe.logic.filter

import com.example.smartwardrobe.data.ClothingItem
import com.example.smartwardrobe.data.WeatherResponse

interface ClothingItemFilter {
    fun filter(
        items: List<ClothingItem>,
        weather: WeatherResponse,
        occasion: String
    ): List<ClothingItem>
}

class WeatherBasedFilter : ClothingItemFilter {
    override fun filter(
        items: List<ClothingItem>,
        weather: WeatherResponse,
        occasion: String
    ): List<ClothingItem> {
        val temp = weather.main.temp
        return items.filter { item ->
            when {
                temp < 15 -> item.thickness != "薄"
                temp > 25 -> item.thickness != "厚"
                else -> true
            }
        }
    }
}