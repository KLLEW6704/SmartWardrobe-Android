package com.example.smartwardrobe.logic.scoring

import com.example.smartwardrobe.data.UserInfo
import com.example.smartwardrobe.data.WeatherResponse
import com.example.smartwardrobe.logic.OutfitRecommender.Outfit

/**
 * 组合评分策略
 * 可以组合多个评分策略，并根据权重计算最终得分
 */
class CompositeScroingStrategy(
    private val strategies: List<Pair<OutfitScoringStrategy, Double>>
) : OutfitScoringStrategy {
    override fun calculateScore(
        outfit: Outfit,
        weather: WeatherResponse,
        userInfo: UserInfo,
        occasion: String
    ): Double = strategies.sumOf { (strategy, weight) ->
        strategy.calculateScore(outfit, weather, userInfo, occasion) * weight
    }
}