package com.example.smartwardrobe.logic.scoring

import com.example.smartwardrobe.data.ClothingItem
import com.example.smartwardrobe.data.UserInfo
import com.example.smartwardrobe.data.WeatherResponse
import com.example.smartwardrobe.logic.OutfitRecommender.Outfit

class StyleMatchingScroingStrategy : OutfitScoringStrategy {
    override fun calculateScore(
        outfit: Outfit,
        weather: WeatherResponse,
        userInfo: UserInfo,
        occasion: String
    ): Double {
        var styleScore = 0.0
        val requiredStyleSet = when (occasion) {
            "正式" -> setOf("正式")
            "休闲" -> setOf("休闲", "户外")
            "户外" -> setOf("户外")
            "上课" -> setOf("休闲", "正式")
            else -> setOf("休闲")
        }

        // 检查每个部件的风格匹配度
        outfit.top?.let { if (it.style in requiredStyleSet) styleScore += 10.0 else styleScore -= 10.0 }
        outfit.bottom?.let { if (it.style in requiredStyleSet) styleScore += 10.0 else styleScore -= 10.0 }
        outfit.outer?.let { if (it.style in requiredStyleSet) styleScore += 10.0 else styleScore -= 10.0 }
        outfit.shoes?.let { if (it.style in requiredStyleSet) styleScore += 10.0 else styleScore -= 10.0 }

        return styleScore.coerceIn(-30.0, 30.0)
    }
}