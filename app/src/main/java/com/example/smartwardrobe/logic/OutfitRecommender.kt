package com.example.smartwardrobe.logic

import com.example.smartwardrobe.data.ClothingItem
import com.example.smartwardrobe.data.UserInfo
import com.example.smartwardrobe.data.WeatherResponse
import com.example.smartwardrobe.logic.filter.ClothingItemFilter
import com.example.smartwardrobe.logic.scoring.OutfitScoringStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class OutfitRecommender(
    private val scoringStrategy: OutfitScoringStrategy,
    private val itemFilter: ClothingItemFilter,
    private val config: OutfitRecommenderConfig = OutfitRecommenderConfig(),
    private val coroutineScope: CoroutineScope? = null
) {
    data class Outfit(
        val top: ClothingItem?,
        val bottom: ClothingItem?,
        val outer: ClothingItem?,
        val shoes: ClothingItem?,
        val accessories: List<ClothingItem> = emptyList(),
        val score: Double
    )

    data class OutfitRecommenderConfig(
        val temperatureWeightRange: ClosedRange<Double> = -30.0..30.0,
        val weatherWeightRange: ClosedRange<Double> = -20.0..20.0,
        val comfortWeightRange: ClosedRange<Double> = -20.0..20.0,
        val styleWeightRange: ClosedRange<Double> = -30.0..30.0,
        val minRequiredItems: Int = 2
    )

    suspend fun recommend(
        weather: WeatherResponse?,
        userInfo: UserInfo,
        items: List<ClothingItem>,
        occasion: String,
        maxResults: Int = 3
    ): List<Outfit> = coroutineScope {
        if (weather == null) return@coroutineScope emptyList()

        // 过滤不合适的衣物
        val filteredItems = itemFilter.filter(items, weather, occasion)

        // 按类别分类
        val tops = filteredItems.filter { it.category == "top" }
        val bottoms = filteredItems.filter { it.category == "bottom" }
        val outers = filteredItems.filter { it.category == "outer" }
        val shoes = filteredItems.filter { it.category == "shoes" }
        val accessories = filteredItems.filter { it.category == "accessory" }

        val needOuter = when {
            weather.main.temp < 15 -> true
            weather.main.temp > 25 -> false
            else -> true
        }

        val outerCandidates = if (needOuter) {
            outers
        } else {
            listOf(null) + outers
        }

        // 使用协程并行处理组合
        val candidateOutfits = coroutineScope?.let { scope ->
            val combinations = mutableListOf<List<ClothingItem?>>()
            for (top in tops) {
                for (bottom in bottoms) {
                    for (outer in outerCandidates) {
                        for (shoe in shoes) {
                            combinations.add(listOf(top, bottom, outer, shoe))
                        }
                    }
                }
            }

            combinations.chunked(100).map { chunk ->
                scope.async {
                    processChunk(chunk, weather, userInfo, occasion)
                }
            }.awaitAll().flatten()
        } ?: processChunk(
            tops.flatMap { top ->
                bottoms.flatMap { bottom ->
                    outerCandidates.flatMap { outer ->
                        shoes.map { shoe ->
                            listOf(top, bottom, outer, shoe)
                        }
                    }
                }
            },
            weather,
            userInfo,
            occasion
        )

        candidateOutfits.sortedByDescending { it.score }.take(maxResults)
    }

    private fun processChunk(
        combinations: List<List<ClothingItem?>>,
        weather: WeatherResponse,
        userInfo: UserInfo,
        occasion: String
    ): List<Outfit> = combinations.mapNotNull { (top, bottom, outer, shoes) ->
        val outfit = OutfitBuilder()
            .withTop(top)
            .withBottom(bottom)
            .withOuter(outer)
            .withShoes(shoes)
            .build()

        val score = scoringStrategy.calculateScore(outfit, weather, userInfo, occasion)

        if (score > 0) {
            OutfitBuilder()
                .withTop(top)
                .withBottom(bottom)
                .withOuter(outer)
                .withShoes(shoes)
                .withScore(score)
                .build()
        } else null
    }
}