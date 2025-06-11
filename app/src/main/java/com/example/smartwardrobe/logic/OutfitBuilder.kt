package com.example.smartwardrobe.logic

import com.example.smartwardrobe.data.ClothingItem
import com.example.smartwardrobe.logic.OutfitRecommender.Outfit

class OutfitBuilder {
    private var top: ClothingItem? = null
    private var bottom: ClothingItem? = null
    private var outer: ClothingItem? = null
    private var shoes: ClothingItem? = null
    private var accessories: List<ClothingItem> = emptyList()
    private var score: Double = 0.0

    fun withTop(top: ClothingItem?) = apply { this.top = top }
    fun withBottom(bottom: ClothingItem?) = apply { this.bottom = bottom }
    fun withOuter(outer: ClothingItem?) = apply { this.outer = outer }
    fun withShoes(shoes: ClothingItem?) = apply { this.shoes = shoes }
    fun withAccessories(accessories: List<ClothingItem>) = apply { this.accessories = accessories }
    fun withScore(score: Double) = apply { this.score = score }

    fun build() = Outfit(top, bottom, outer, shoes, accessories, score)
}