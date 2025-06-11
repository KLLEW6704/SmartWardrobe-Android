package com.example.smartwardrobe.logic.scoring

import com.example.smartwardrobe.data.ClothingItem
import com.example.smartwardrobe.data.UserInfo
import com.example.smartwardrobe.data.WeatherResponse
import com.example.smartwardrobe.logic.OutfitRecommender.Outfit

class UserPreferenceScroingStrategy : OutfitScoringStrategy {
    override fun calculateScore(
        outfit: Outfit,
        weather: WeatherResponse,
        userInfo: UserInfo,
        occasion: String
    ): Double {
        val items = listOfNotNull(outfit.top, outfit.bottom, outfit.outer, outfit.shoes)

        val score = when (userInfo.comfort) {
            "怕冷" -> {
                items.sumOf {
                    if (it.thickness == "薄") -10.0 else +5.0
                }
            }
            "怕热" -> {
                items.sumOf {
                    if (it.thickness == "厚") -10.0 else +5.0
                }
            }
            else -> {
                items.sumOf { +5.0 }
            }
        }

        return score.coerceIn(-20.0, 20.0)
    }
}