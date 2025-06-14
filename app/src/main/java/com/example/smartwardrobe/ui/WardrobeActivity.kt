package com.example.smartwardrobe.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.smartwardrobe.BuildConfig
import com.example.smartwardrobe.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// Activity实现了公共的OnItemInteractionListener接口
class WardrobeActivity : AppCompatActivity(), OnItemInteractionListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WardrobeAdapter
    private var clothingItems = mutableListOf<WardrobeItem>()
    private lateinit var currentPhotoPath: String
    private lateinit var zhipuApiService: ZhipuApiService
    private var loadingDialog: AlertDialog? = null

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            analyzeClothingImage(currentPhotoPath)
        }
    }

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 2
        private const val IMAGE_MAX_SIZE = 1200
        private const val IMAGE_QUALITY = 90
    }

    // --- Activity 生命周期方法 ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wardrobe)

        initViewsAndListeners()
        initZhipuService()
        loadClothingItems()
    }

    // --- OnItemInteractionListener 接口的实现 ---
    override fun onEditClick(item: WardrobeItem) {
        val position = clothingItems.indexOf(item)
        if (position != -1) {
            showEditDialog(item, position)
        }
    }

    override fun onDeleteClick(item: WardrobeItem, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("删除衣物")
            .setMessage("确定要删除 “${item.name}” 吗？")
            .setPositiveButton("删除") { _, _ ->
                // 从列表中移除
                clothingItems.removeAt(position)
                adapter.notifyItemRemoved(position)
                // 删除关联的图片文件
                try {
                    File(item.imageUri).delete()
                } catch (e: Exception) {
                    Log.e("FileDelete", "删除图片失败", e)
                }
                // 保存更改
                saveItems()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }


    // --- 初始化相关方法 ---
    private fun initViewsAndListeners() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "我的衣柜"

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        findViewById<FloatingActionButton>(R.id.fabCamera).setOnClickListener {
            checkCameraPermissionAndTakePhoto()
        }
    }

    private fun initZhipuService() {
        val zhipuClient = OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        val zhipuRetrofit = Retrofit.Builder()
            .baseUrl("https://open.bigmodel.cn/api/paas/v4/")
            .client(zhipuClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        zhipuApiService = zhipuRetrofit.create(ZhipuApiService::class.java)
    }

    // --- AI 识别相关方法 ---
    private fun analyzeClothingImage(imagePath: String) {
        val imageFile = File(imagePath)
        if (!imageFile.exists()) {
            Toast.makeText(this, "图片文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val compressedImage = compressImage(imageFile) ?: run {
            Toast.makeText(this, "图片压缩失败", Toast.LENGTH_SHORT).show()
            return
        }

        showLoadingDialog("正在识别衣物...")

        lifecycleScope.launch(Dispatchers.IO) {
            val base64Image = Base64.encodeToString(compressedImage, Base64.NO_WRAP)
            val apiKey = BuildConfig.ZHIPU_API_KEY
            val token = generateZhipuToken(apiKey)
            if (token == null) {
                withContext(Dispatchers.Main) {
                    hideLoadingDialog()
                    Toast.makeText(this@WardrobeActivity, "API Key无效或格式错误", Toast.LENGTH_LONG).show()
                }
                return@launch
            }


            val analysisPrompt = createAnalysisPrompt()
            // 1. 创建 content part 列表
            val contentParts = listOf(
                ZhipuContentPart(type = "text", text = analysisPrompt),
                ZhipuContentPart(type = "image_url", imageUrl = ZhipuImageUrl(url = "data:image/jpeg;base64,$base64Image"))
            )

            // 2. 创建 image message，注意这里的参数是 content
            val messages = listOf(ZhipuImageMessage(content = contentParts))

            // 3. 创建最终的 image request
            val request = ZhipuImageRequest(messages = messages)

            // 4. 调用我们刚刚在接口中定义的、完全匹配的方法
            val call = zhipuApiService.getImageCompletion("Bearer $token", request)

            // 后续的 withContext(Dispatchers.Main) { call.enqueue(...) } 逻辑保持不变
            withContext(Dispatchers.Main) {
                zhipuApiService.getImageCompletion("Bearer $token", request).enqueue(object : Callback<ZhipuResponse> {
                    override fun onResponse(call: Call<ZhipuResponse>, response: Response<ZhipuResponse>) {
                        hideLoadingDialog()
                        if (response.isSuccessful) {
                            val rawContent = response.body()?.choices?.firstOrNull()?.message?.content
                            Log.d("AI_Response", "AI成功返回原始文本: $rawContent")
                            val cleanJson = rawContent?.substringAfter("{")?.substringBeforeLast("}")?.let { "{${it}}" }
                            if (cleanJson != null) {
                                try {
                                    val aiResult = Gson().fromJson(cleanJson, AiClothingAnalysis::class.java)
                                    showConfirmationDialog(aiResult, imagePath)
                                } catch (e: Exception) {
                                    handleRecognitionError(imagePath, "解析AI返回结果失败")
                                }
                            } else {
                                handleRecognitionError(imagePath, "AI返回内容格式不正确")
                            }
                        } else {
                            handleRecognitionError(imagePath, "AI识别失败: ${response.code()}")
                        }
                    }

                    override fun onFailure(call: Call<ZhipuResponse>, t: Throwable) {
                        hideLoadingDialog()
                        handleRecognitionError(imagePath, "网络请求失败")
                    }
                })
            }
        }
    }

    private fun createAnalysisPrompt(): String {
        return """
        你是一位专业的衣物分析师。请仔细分析这张图片，并根据下方提供的“JSON模板”和每个属性的“可选值”，为这件衣物生成一个JSON对象。
        **重要规则:**
        1. 对于 "style", "thickness", "category" 字段，你必须严格从下方提供的“可选值”中选择最贴切的一项。
        2. 对于 "name" 字段，请根据图片内容生成一个简洁但描述准确的名称，例如“灰色连帽卫衣”或“蓝色修身牛仔裤”。
        3. 你的回复必须且只能是一个完整的、不含任何额外注释或文字的JSON对象。
        ---
        **JSON模板：**
        {
          "name": "",
          "style": "",
          "thickness": "",
          "category": ""
        }
        ---
        **属性可选值：**
        "style": [ "休闲", "正式", "运动", "时尚" ]
        "thickness": [ "薄款", "常规", "加厚" ]
        "category": [ "上衣", "裤子", "外套", "裙子", "鞋子" ]
        """.trimIndent()
    }


    // --- 对话框相关方法 ---

    private fun showConfirmationDialog(clothingAnalysis: AiClothingAnalysis, imagePath: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_clothing, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etName)
        val spinnerStyle = dialogView.findViewById<Spinner>(R.id.spinnerStyle)
        val spinnerThickness = dialogView.findViewById<Spinner>(R.id.spinnerThickness)
        val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinnerCategory)

        etName.setText(clothingAnalysis.name ?: "")

        val styles = arrayOf("休闲", "正式", "运动", "时尚")
        ArrayAdapter(this, android.R.layout.simple_spinner_item, styles).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerStyle.adapter = it
            spinnerStyle.setSelection(styles.indexOf(clothingAnalysis.style).coerceAtLeast(0))
        }

        val thicknesses = arrayOf("薄款", "常规", "加厚")
        ArrayAdapter(this, android.R.layout.simple_spinner_item, thicknesses).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerThickness.adapter = it
            spinnerThickness.setSelection(thicknesses.indexOf(clothingAnalysis.thickness).coerceAtLeast(0))
        }

        val categories = arrayOf("上衣", "裤子", "外套", "裙子", "鞋子")
        ArrayAdapter(this, android.R.layout.simple_spinner_item, categories).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerCategory.adapter = it
            spinnerCategory.setSelection(categories.indexOf(clothingAnalysis.category).coerceAtLeast(0))
        }

        AlertDialog.Builder(this)
            .setTitle("确认衣物信息")
            .setView(dialogView)
            .setPositiveButton("确认") { _, _ ->
                val name = etName.text?.toString()
                if (name.isNullOrBlank()) {
                    Toast.makeText(this, "请输入衣物名称", Toast.LENGTH_SHORT).show()
                } else {
                    val newItem = WardrobeItem(
                        name = name,
                        style = spinnerStyle.selectedItem.toString(),
                        thickness = spinnerThickness.selectedItem.toString(),
                        category = spinnerCategory.selectedItem.toString(),
                        imageUri = imagePath
                    )
                    // 修改这里：使用 add(0, newItem) 替代 add(newItem)
                    clothingItems.add(0, newItem)
                    // 通知适配器在开头插入了新项目
                    adapter.notifyItemInserted(0)
                    // 滚动到顶部显示新添加的衣物
                    recyclerView.scrollToPosition(0)
                    saveItems()
                    Toast.makeText(this, "已添加新衣物", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                File(imagePath).delete()
            }
            .setCancelable(false)
            .create()
            .show()
    }

    private fun showEditDialog(item: WardrobeItem, position: Int) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_clothing, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etName)
        val spinnerStyle = dialogView.findViewById<Spinner>(R.id.spinnerStyle)
        val spinnerThickness = dialogView.findViewById<Spinner>(R.id.spinnerThickness)
        val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinnerCategory)

        etName.setText(item.name)

        // 设置样式选择器
        val styles = arrayOf("休闲", "正式", "运动", "时尚")
        ArrayAdapter(this, android.R.layout.simple_spinner_item, styles).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerStyle.adapter = it
            spinnerStyle.setSelection(styles.indexOf(item.style).coerceAtLeast(0))
        }

        // 设置厚度选择器
        val thicknesses = arrayOf("薄款", "常规", "加厚")
        ArrayAdapter(this, android.R.layout.simple_spinner_item, thicknesses).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerThickness.adapter = it
            spinnerThickness.setSelection(thicknesses.indexOf(item.thickness).coerceAtLeast(0))
        }

        // 设置类别选择器
        val categories = arrayOf("上衣", "裤子", "外套", "裙子", "鞋子")
        ArrayAdapter(this, android.R.layout.simple_spinner_item, categories).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerCategory.adapter = it
            spinnerCategory.setSelection(categories.indexOf(item.category).coerceAtLeast(0))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑衣物")
            .setView(dialogView)
            .setPositiveButton(R.string.save) { dialog, _ ->
                val updatedItem = item.copy(
                    name = etName.text.toString(),
                    style = spinnerStyle.selectedItem.toString(),
                    thickness = spinnerThickness.selectedItem.toString(),
                    category = spinnerCategory.selectedItem.toString()
                )
                clothingItems[position] = updatedItem
                adapter.notifyItemChanged(position)
                saveItems()
                Toast.makeText(this, "修改成功", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.delete) { dialog, _ ->
                // 显示删除确认对话框
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_clothing)
                    .setMessage(getString(R.string.confirm_delete_clothing, item.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        // 从列表中移除
                        clothingItems.removeAt(position)
                        adapter.notifyItemRemoved(position)
                        // 删除关联的图片文件
                        try {
                            File(item.imageUri).delete()
                        } catch (e: Exception) {
                            Log.e("FileDelete", "删除图片失败", e)
                        }
                        // 保存更改
                        saveItems()
                        Toast.makeText(this@WardrobeActivity,
                            getString(R.string.delete_success),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .create()
                    .show()
            }
            .create()

        dialog.show()
    }


    // --- 文件和数据持久化方法 ---

    private fun loadClothingItems() {
        try {
            val prefs = getSharedPreferences("clothing_prefs", Context.MODE_PRIVATE)
            val jsonString = prefs.getString("clothing_items", null)

            clothingItems = mutableListOf()

            if (jsonString != null) {
                try {
                    val type = object : TypeToken<MutableList<WardrobeItem>>() {}.type
                    val loadedItems: MutableList<WardrobeItem>? = Gson().fromJson(jsonString, type)
                    if (loadedItems != null) {
                        // 反转列表顺序，使最新添加的在前面
                        clothingItems.addAll(loadedItems.reversed())
                    }
                } catch (e: Exception) {
                    Log.e("WardrobeActivity", "Error parsing saved items", e)
                }
            } else {
                val defaultItems = loadDefaultItems()
                // 同样反转默认列表
                clothingItems.addAll(defaultItems.reversed())
            }

            adapter = WardrobeAdapter(clothingItems, this)
            recyclerView.adapter = adapter

            if (jsonString == null) {
                saveItems()
            }

        } catch (e: Exception) {
            Log.e("WardrobeActivity", "Error in loadClothingItems", e)
            clothingItems = mutableListOf()
            adapter = WardrobeAdapter(clothingItems, this)
            recyclerView.adapter = adapter
            Toast.makeText(this, "加载衣物列表失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadDefaultItems(): MutableList<WardrobeItem> {
        return try {
            val jsonString = assets.open("default_clothing.json").bufferedReader().use { it.readText() }
            try {
                val type = object : TypeToken<MutableList<WardrobeItem>>() {}.type
                Gson().fromJson<MutableList<WardrobeItem>>(jsonString, type) ?: mutableListOf()
            } catch (e: Exception) {
                Log.e("WardrobeActivity", "Error parsing default items", e)
                mutableListOf()
            }
        } catch (e: Exception) {
            Log.e("WardrobeActivity", "Error loading default items", e)
            mutableListOf()
        }
    }

    private fun saveItems() {
        val prefs = getSharedPreferences("clothing_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("clothing_items", Gson().toJson(clothingItems)).apply()
    }

// 将以下所有方法粘贴到 WardrobeActivity 类的内部

    private fun showLoadingDialog(message: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_loading, null)
        dialogView.findViewById<TextView>(R.id.textViewMessage).text = message
        loadingDialog = AlertDialog.Builder(this).setView(dialogView).setCancelable(false).create()
        loadingDialog?.show()
    }
    private fun hideLoadingDialog() { loadingDialog?.dismiss(); loadingDialog = null }

    private fun handleRecognitionError(imagePath: String, errorMessage: String?) {
        AlertDialog.Builder(this)
            .setTitle("识别失败")
            .setMessage("是否要重试？\n\n错误: ${errorMessage ?: "未知错误"}")
            .setPositiveButton("重试") { _, _ -> analyzeClothingImage(imagePath) }
            .setNegativeButton("取消") { _, _ -> File(imagePath).delete() }
            .create()
            .show()
    }

    private fun checkCameraPermissionAndTakePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else {
            dispatchTakePictureIntent()
        }
    }

    private fun dispatchTakePictureIntent() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            takePictureIntent.resolveActivity(packageManager)?.also {
                val photoFile: File? = try {
                    createImageFile()
                } catch (ex: IOException) {
                    null
                }
                photoFile?.also {
                    val photoURI: Uri = FileProvider.getUriForFile(this, "com.example.smartwardrobe.fileprovider", it)
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    takePictureLauncher.launch(takePictureIntent)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir).apply {
            currentPhotoPath = absolutePath
        }
    }

    private fun generateZhipuToken(apiKey: String): String? {
        return try {
            val (id, secret) = apiKey.trim().split(".").takeIf { it.size == 2 } ?: return null
            val algorithm = Algorithm.HMAC256(secret)
            val now = System.currentTimeMillis()
            JWT.create()
                .withHeader(mapOf("alg" to "HS256", "sign_type" to "SIGN"))
                .withClaim("api_key", id)
                .withClaim("exp", now + 3600 * 1000)
                .withClaim("timestamp", now)
                .sign(algorithm)
        } catch (e: Exception) {
            null
        }
    }

    private fun compressImage(imageFile: File): ByteArray? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, options)
            options.inSampleSize = calculateInSampleSize(options, IMAGE_MAX_SIZE, IMAGE_MAX_SIZE)
            options.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options)
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, stream)
                stream.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

}