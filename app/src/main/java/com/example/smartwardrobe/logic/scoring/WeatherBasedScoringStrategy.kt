package com.example.smartwardrobe.logic.scoring

import com.example.smartwardrobe.data.UserInfo
import com.example.smartwardrobe.data.WeatherResponse
import com.example.smartwardrobe.logic.OutfitRecommender.Outfit

/**
 * 基于天气的评分策略
 * 根据温度和天气状况对搭配进行评分
 */
class WeatherBasedScoringStrategy : OutfitScoringStrategy {
    override fun calculateScore(
        outfit: Outfit,
        weather: WeatherResponse,
        userInfo: UserInfo,
        occasion: String
    ): Double {
        val temp = weather.main.temp
        val condition = weather.weather.firstOrNull()?.main?.lowercase() ?: ""

        return calculateTemperatureScore(outfit, temp) +
                calculateWeatherConditionScore(outfit, condition)
    }

    private fun calculateTemperatureScore(outfit: Outfit, temp: Double): Double {
        val items = listOfNotNull(outfit.top, outfit.bottom, outfit.outer, outfit.shoes)

        val score = when {
            temp < 15 -> {
                items.sumOf {
                    when (it.thickness) {
                        "薄" -> -10.0
                        "中" -> +5.0
                        "厚" -> +10.0
                        else -> 0.0
                    }
                }
            }
            temp > 25 -> {
                items.sumOf {
                    when (it.thickness) {
                        "厚" -> -10.0
                        "中" -> +5.0
                        "薄" -> +10.0
                        else -> 0.0
                    }
                }
            }
            else -> {
                items.sumOf {
                    when (it.thickness) {
                        "中" -> +10.0
                        else -> +5.0
                    }
                }
            }
        }

        return score.coerceIn(-30.0, 30.0)
    }

    private fun calculateWeatherConditionScore(outfit: Outfit, condition: String): Double {
        val score = when (condition) {
            "rain", "drizzle", "thunderstorm" -> {
                if (outfit.outer?.let { it.style in listOf("户外", "休闲") && it.thickness != "薄" } == true) {
                    +15.0
                } else {
                    -10.0
                }
            }
            "snow" -> {
                if (outfit.outer?.thickness == "厚") +20.0 else -15.0
            }
            "clear", "clouds" -> +5.0
            else -> 0.0
        }

        return score.coerceIn(-20.0, 20.0)
    }
}