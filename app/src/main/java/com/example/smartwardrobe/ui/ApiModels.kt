// ApiModels.kt (最终版)
package com.example.smartwardrobe.ui

import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

// --- 公共数据模型 ---
data class ZhipuRequest(
    val model: String,
    val messages: List<ZhipuMessage>,
    val temperature: Double? = null,
    val maxTokens: Int? = null
)

data class WardrobeItem(
    val name: String = "",
    val style: String = "",
    val thickness: String = "",
    val category: String = "",
    val imageUri: String = ""
)

interface OnItemInteractionListener {
    fun onEditClick(item: WardrobeItem)
    fun onDeleteClick(item: WardrobeItem, position: Int)
}

data class AiClothingAnalysis(
    val name: String?,
    val style: String?,
    val thickness: String?,
    val category: String?
)

// --- Zhipu AI API 相关 ---

// 用于图片识别的请求结构
data class ZhipuImageRequest(
    val model: String = "glm-4v",
    val messages: List<ZhipuImageMessage>
)

data class ZhipuImageMessage(
    val role: String = "user",
    val content: List<ZhipuContentPart> // ZhipuImageMessage 包含一个 content 列表
)

data class ZhipuContentPart(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url")
    val imageUrl: ZhipuImageUrl? = null
)

data class ZhipuImageUrl(val url: String)


// 通用响应结构
data class ZhipuResponse(val choices: List<ZhipuChoice>)
data class ZhipuChoice(val message: ZhipuResponseMessage)
data class ZhipuResponseMessage(val content: String)


// 最终的 API 服务接口
interface ZhipuApiService {
    @POST("chat/completions")
    fun getCompletion(
        @Header("Authorization") token: String,
        @Body request: ZhipuRequest
    ): Call<ZhipuResponse>

    @POST("chat/completions")
    fun getImageCompletion(
        @Header("Authorization") token: String,
        @Body request: ZhipuImageRequest // 明确接收 ZhipuImageRequest 类型
    ): Call<ZhipuResponse>

    // 如果您还有纯文本的API调用，可以再添加一个方法
    // fun getTextCompletion(...)
}