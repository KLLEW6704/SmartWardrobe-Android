package com.example.smartwardrobe.logic.scoring

import com.example.smartwardrobe.data.UserInfo
import com.example.smartwardrobe.data.WeatherResponse
import com.example.smartwardrobe.logic.OutfitRecommender.Outfit

/**
 * 搭配评分策略接口
 * 负责计算一套搭配的得分
 */
interface OutfitScoringStrategy {
    /**
     * 计算搭配得分
     * @param outfit 需要评分的搭配
     * @param weather 当前天气情况
     * @param userInfo 用户信息
     * @param occasion 场合
     * @return 评分结果
     */
    fun calculateScore(
        outfit: Outfit,
        weather: WeatherResponse,
        userInfo: UserInfo,
        occasion: String
    ): Double
}