package com.example.smartwardrobe.logic

object ZhipuPromptHelper {
    fun buildZhipuOutfitPrompt(
        gender: String,
        bodyType: String,
        city: String,
        weatherDesc: String,
        temperature: String,
        occasion: String,
        wardrobeJson: String
    ): String {
        return """
你是一位专业的智能穿搭顾问。请结合以下全部信息，从用户衣柜里推荐最合适的一套穿搭，并说明理由。

【用户信息】
- 性别：$gender
- 体质：$bodyType

【当前天气】
- 地点：$city
- 天气：$weatherDesc
- 温度：$temperature°C

【穿衣场合】
- 场合：$occasion

【可选衣物清单】
$wardrobeJson

请严格只从衣柜清单中选择衣物，优先考虑穿着舒适度和场合适用性。推荐结果格式如下：

上装: 【衣物名】
下装: 【衣物名】
外套: 【衣物名或无】
鞋子: 【衣物名】
配饰: 【如有】

推荐理由: 【简要说明如何结合天气、体质、场合和衣柜做出选择】
""".trimIndent()
    }
}