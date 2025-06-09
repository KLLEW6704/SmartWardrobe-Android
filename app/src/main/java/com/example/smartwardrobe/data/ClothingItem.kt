// app/src/main/java/com/example/smartwardrobe/data/ClothingItem.kt
package com.example.smartwardrobe.data

/**
 * @param name      衣物名称
 * @param style     风格，例如 “正式” / “休闲” / “运动” / “户外” 等
 * @param thickness 厚度，例如 “薄” / “中” / “厚”
 * @param category  类别，例如 “top” (上装) / “bottom” (下装) / “outer” (外套) / “shoes” (鞋子) / “accessory” (配饰)
 * @param imageUri  可选：图片 URI（简化时可以留空）
 */
data class ClothingItem(
    val name: String,
    val style: String,
    val thickness: String,
    val category: String,
    val imageUri: String?= null
)
