package com.example.smartwardrobe.ui

import android.content.Context
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.smartwardrobe.R
import com.example.smartwardrobe.data.ClothingItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AddClothingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "clothing_prefs"
        private const val KEY_ITEMS = "clothing_items"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_clothing)

        // 找到各控件
        val etName = findViewById<EditText>(R.id.etName)
        val spStyle = findViewById<Spinner>(R.id.spStyle)
        val spThickness = findViewById<Spinner>(R.id.spThickness)
        val spCategory = findViewById<Spinner>(R.id.spCategory)
        val btnSave = findViewById<Button>(R.id.btnSave)

        // Spinner 数据（可按需修改）
        val styles = listOf("正式", "休闲", "户外", "运动")
        val thicknesses = listOf("薄", "中", "厚")
        val categories = listOf("top", "bottom", "outer", "shoes", "accessory")

        // 给各 Spinner 设置 Adapter
        spStyle.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, styles)
        spThickness.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, thicknesses)
        spCategory.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入衣物名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val style = spStyle.selectedItem as String
            val thickness = spThickness.selectedItem as String
            val category = spCategory.selectedItem as String

            // 因为暂时不处理图片，这里 imageUri 直接先用空串
            val newItem = ClothingItem(name, style, thickness, category, "")

            // 先从 SharedPreferences 里把已有的衣物列表取出来
            val existing = loadUserItemsFromLocal().toMutableList()
            existing.add(newItem)
            // 保存回去
            saveUserItemsToLocal(existing)

            Toast.makeText(this, "已添加：$name", Toast.LENGTH_SHORT).show()
            // 添加完后可直接跳回推荐页，触发重新推荐
            finish()
        }
    }

    /**
     * 从 SharedPreferences 中读取衣物列表（与之前示例一致）
     */
    private fun loadUserItemsFromLocal(): List<ClothingItem> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ITEMS, null)
        if (json.isNullOrEmpty()) return emptyList()
        val type = object : TypeToken<List<ClothingItem>>() {}.type
        return Gson().fromJson(json, type)
    }

    /**
     * 将衣物列表保存到 SharedPreferences
     */
    private fun saveUserItemsToLocal(items: List<ClothingItem>) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val json = Gson().toJson(items)
        editor.putString(KEY_ITEMS, json)
        editor.apply()
    }
}
