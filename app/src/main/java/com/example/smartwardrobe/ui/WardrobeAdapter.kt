package com.example.smartwardrobe.ui

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smartwardrobe.R
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast

class WardrobeAdapter(
    private var items: MutableList<RecommendActivity.ClothingItem>,
    private val context: Context
) : RecyclerView.Adapter<WardrobeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.tvName)
        val detailsText: TextView = view.findViewById(R.id.tvDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_clothing, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.nameText.text = item.name
        holder.detailsText.text = "${item.category} • ${item.style}风格 • ${item.thickness}厚度"

        // 添加点击事件
        holder.itemView.setOnClickListener {
            showEditDialog(position, item)
        }
    }

    override fun getItemCount() = items.size

    private fun showEditDialog(position: Int, item: RecommendActivity.ClothingItem) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_clothing, null)

        // 获取视图引用
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etName)
        val spinnerStyle = dialogView.findViewById<Spinner>(R.id.spinnerStyle)
        val spinnerThickness = dialogView.findViewById<Spinner>(R.id.spinnerThickness)
        val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinnerCategory)

        // 设置当前值
        etName.setText(item.name)

        // 设置风格选择器
        val styles = arrayOf("休闲", "正式", "运动", "时尚")
        val styleAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, styles)
        styleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerStyle.adapter = styleAdapter
        spinnerStyle.setSelection(styles.indexOf(item.style).coerceAtLeast(0))

        // 设置厚度选择器
        val thicknesses = arrayOf("薄款", "常规", "加厚")
        val thicknessAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, thicknesses)
        thicknessAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerThickness.adapter = thicknessAdapter
        spinnerThickness.setSelection(thicknesses.indexOf(item.thickness).coerceAtLeast(0))

        // 设置类别选择器
        val categories = arrayOf("上衣", "裤子", "外套", "裙子", "鞋子")
        val categoryAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, categories)
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategory.adapter = categoryAdapter
        spinnerCategory.setSelection(categories.indexOf(item.category).coerceAtLeast(0))

        val dialog = AlertDialog.Builder(context)
            .setTitle("编辑衣物")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val updatedItem = RecommendActivity.ClothingItem(
                    name = etName.text?.toString().orEmpty(),
                    style = spinnerStyle.selectedItem?.toString().orEmpty(),
                    thickness = spinnerThickness.selectedItem?.toString().orEmpty(),
                    category = spinnerCategory.selectedItem?.toString().orEmpty(),
                    imageUri = item.imageUri
                )

                // 更新列表和存储
                items[position] = updatedItem
                notifyItemChanged(position)
                saveItems()

                Toast.makeText(context, "保存成功", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("删除") { _, _ ->
                // 显示删除确认对话框
                showDeleteConfirmationDialog(position, item)
            }
            .create()

        dialog.show()
    }

    private fun showDeleteConfirmationDialog(position: Int, item: RecommendActivity.ClothingItem) {
        AlertDialog.Builder(context)
            .setTitle("删除衣物")
            .setMessage("确定要删除这件衣物吗？\n${item.name}")
            .setPositiveButton("确定") { _, _ ->
                // 删除图片文件（如果存在）
                if (!item.imageUri.isNullOrEmpty()) {
                    try {
                        val file = java.io.File(item.imageUri)
                        if (file.exists()) {
                            file.delete()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 从列表中移除
                items.removeAt(position)
                notifyItemRemoved(position)
                notifyItemRangeChanged(position, items.size)

                // 保存更新后的列表
                saveItems()

                Toast.makeText(context, "删除成功", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 确保添加必要的 imports
    private fun saveItems() {
        val prefs = context.getSharedPreferences("clothing_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("clothing_items", Gson().toJson(items)).apply()
    }
}