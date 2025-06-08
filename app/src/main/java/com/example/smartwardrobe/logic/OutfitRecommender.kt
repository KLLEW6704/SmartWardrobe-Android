package com.example.smartwardrobe.logic

import com.example.smartwardrobe.data.ClothingItem
import com.example.smartwardrobe.data.UserInfo
import com.example.smartwardrobe.data.WeatherResponse

/**
 * 多维度搭配引擎：
 *   - 将所有衣物根据 category 分类
 *   - 逐个枚举可能的套装组合（简化起见，只枚举 上装(top)、下装(bottom)、外套(outer)、鞋子(shoes) 四个类别；
 *     如果某些天气无需外套則外套可为 null）
 *   - 对每个“候选套装”，按“温度匹配 + 天气状况 + 用户体感 + 场合风格” 计算综合得分 score
 *   - 最后返回得分最高的前 N 套
 *
 * 你可以在此基础上再扩展“配饰(accessory)”等。
 */
object OutfitRecommender {

    // 最终返回至 UI 的单套 Outfit
    data class Outfit(
        val top: ClothingItem?,
        val bottom: ClothingItem?,
        val outer: ClothingItem?,
        val shoes: ClothingItem?,
        val accessories: List<ClothingItem> = emptyList(), // 可选
        val score: Double
    )

    /**
     * 推荐最多 maxResults 套装
     *
     * @param weather   来自 WiatherService 的天气数据
     * @param userInfo  用户信息（性别、体感）
     * @param items     用户衣物清单（必须包含 category 信息）
     * @param occasion  场合，例如 “正式”/“休闲”/“户外”/“上课” 等
     * @param maxResults 最多返回多少套组合
     */
    fun recommend(
        weather: WeatherResponse?,
        userInfo: UserInfo,
        items: List<ClothingItem>,
        occasion: String,
        maxResults: Int = 3
    ): List<Outfit> {
        if (weather == null) return emptyList()

        val temp = weather.main.temp
        val condition = weather.weather.firstOrNull()?.main ?: ""

        // 根据 category 把衣物分桶
        val tops = items.filter { it.category == "top" }
        val bottoms = items.filter { it.category == "bottom" }
        val outers = items.filter { it.category == "outer" }
        val shoes = items.filter { it.category == "shoes" }
        val accessories = items.filter { it.category == "accessory" }

        val candidateOutfits = mutableListOf<Outfit>()

        // 如果温度很高（比如 >25°C），可以考虑“外套”可选为空
        // 如果温度很低（比如 <15°C），必须搭配外套；中间温度可以自行判断是否需要外套
        val needOuter = when {
            temp < 15 -> true
            temp > 25 -> false
            else -> true // 15~25 仍建议配外套，但你可以视情况改为 false
        }

        // 如果不需要外套，就把 outer 列表加上一个 “null” 表示可不穿外套
        val outerCandidates = if (needOuter) {
            outers
        } else {
            listOf<ClothingItem?>(null) + outers // null 表示不穿外套
        }

        // 枚举所有“顶（top）、底（bottom）、外（outer）、鞋（shoes）”的组合
        for (top in tops) {
            for (bottom in bottoms) {
                for (outer in outerCandidates) {
                    for (shoe in shoes) {
                        // 创建初步的 Outfit， accessories 暂不考虑
                        val outfitItems = listOfNotNull(top, bottom, outer, shoe)

                        // 过滤：如果某一必需项本身不存在，则直接 continue
                        if (needOuter && outer == null) continue

                        // 对当前组合计算一个得分
                        val score = calculateScore(
                            temp,
                            condition,
                            userInfo,
                            occasion,
                            top,
                            bottom,
                            outer,
                            shoe,
                            accessories
                        )
                        candidateOutfits.add(Outfit(top, bottom, outer, shoe, emptyList(), score))
                    }
                }
            }
        }

        // 按得分从高到低排序，取前 maxResults 个
        return candidateOutfits
            .sortedByDescending { it.score }
            .take(maxResults)
    }

    /**
     * 计算这一套 Outfit 的综合得分
     *
     * 得分组成（示例权重，可自行调整）：
     *   1. 温度匹配度（0~30 分）
     *   2. 天气状况（0~20 分）
     *   3. 用户体感（0~20 分）
     *   4. 场合风格匹配（0~30 分）
     */
    private fun calculateScore(
        temp: Double,
        condition: String,
        userInfo: UserInfo,
        occasion: String,
        top: ClothingItem,
        bottom: ClothingItem,
        outer: ClothingItem?,
        shoe: ClothingItem,
        allAccessories: List<ClothingItem>
    ): Double {
        var score = 0.0

        // —— 1. 温度匹配度（0~30） ——
        //   如果温度<15，薄衣扣分；温度>25，厚衣扣分；中间温度中/薄/厚皆可
        score += when {
            temp < 15 -> {
                // 15°C 以下，薄衣物（thickness="薄"）要大幅扣分，厚的更合适
                listOf(top, bottom, outer, shoe).sumOf {
                    when (it?.thickness) {
                        "薄" -> -10.0
                        "中" -> +5.0
                        "厚" -> +10.0
                        else -> 0.0
                    }
                }
            }
            temp > 25 -> {
                // 25°C 以上，厚衣物扣分
                listOf(top, bottom, outer, shoe).sumOf {
                    when (it?.thickness) {
                        "厚" -> -10.0
                        "中" -> +5.0
                        "薄" -> +10.0
                        else -> 0.0
                    }
                }
            }
            else -> {
                // 15~25 之间，优先中等厚度
                listOf(top, bottom, outer, shoe).sumOf {
                    when (it?.thickness) {
                        "中" -> +10.0
                        else -> +5.0
                    }
                }
            }
        }.coerceIn(-30.0, 30.0) // 限制值域避免过高过低

        // —— 2. 天气状况（0~20） ——
        // 例如：遇到“雨”、“雪”时，如果外套具备“防水”属性或厚实，则增分；否则扣分
        score += when (condition.lowercase()) {
            "rain", "drizzle", "thunderstorm" -> {
                // 雨天：如果外套厚且风格允许雨衣（style=“户外”或“休闲”）可加分
                if (outer != null && outer.style in listOf("户外", "休闲") && outer.thickness != "薄") {
                    +15.0
                } else {
                    -10.0
                }
            }
            "snow" -> {
                // 下雪：如果厚外套加分，否则扣更大分
                if (outer != null && outer.thickness == "厚") +20.0 else -15.0
            }
            "clear", "clouds" -> {
                // 晴/多云：无特殊要求，中性加分
                +5.0
            }
            else -> +0.0
        }.coerceIn(-20.0, 20.0)

        // —— 3. 用户体感匹配（0~20） ——
        //   用户若 “怕冷”，则所有“薄”衣物扣分；若“怕热”，则“厚”衣物扣分，否则小幅加分
        score += when (userInfo.comfort) {
            "怕冷" -> {
                listOf(top, bottom, outer, shoe).sumOf {
                    if (it?.thickness == "薄") -10.0 else +5.0
                }
            }
            "怕热" -> {
                listOf(top, bottom, outer, shoe).sumOf {
                    if (it?.thickness == "厚") -10.0 else +5.0
                }
            }
            else -> {
                // “中等”则对厚/薄都不苛求
                listOf(top, bottom, outer, shoe).sumOf { +5.0 }
            }
        }.coerceIn(-20.0, 20.0)

        // —— 4. 场合风格匹配（0~30） ——
        //   只要 top、bottom、outer、shoe 都与 occasion 的风格匹配，就给满分
        //   部分匹配时扣分，比如底装风格错了就扣 10 分；外套风格错再扣 10；鞋子错扣 10
        var styleScore = 0.0
        val requiredStyleSet = when (occasion) {
            "正式" -> setOf("正式")
            "休闲" -> setOf("休闲", "户外") // 休闲场合可以接受户外风
            "户外" -> setOf("户外")
            "上课" -> setOf("休闲", "正式") // 上课场合兼顾休闲与半正式
            else -> setOf("休闲")
        }
        // 检查 top
        if (top.style in requiredStyleSet) styleScore += 10.0 else styleScore -= 10.0
        // 检查 bottom
        if (bottom.style in requiredStyleSet) styleScore += 10.0 else styleScore -= 10.0
        // 检查 outer（如果需要外套且选择了外套）
        if (outer != null) {
            if (outer.style in requiredStyleSet) styleScore += 10.0 else styleScore -= 10.0
        }
        // 检查鞋
        if (shoe.style in requiredStyleSet) styleScore += 10.0 else styleScore -= 10.0

        score += styleScore.coerceIn(-30.0, 30.0)

        return score
    }
}
