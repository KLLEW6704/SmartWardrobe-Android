package com.example.smartwardrobe.ui

import android.widget.ArrayAdapter
import android.widget.Spinner
import com.google.android.material.textfield.TextInputEditText
import android.app.AlertDialog
import android.content.DialogInterface
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smartwardrobe.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import android.app.ProgressDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.smartwardrobe.BuildConfig
import com.example.smartwardrobe.data.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit


class WardrobeActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WardrobeAdapter
    private var clothingItems = mutableListOf<RecommendActivity.ClothingItem>()
    private lateinit var currentPhotoPath: String
    private lateinit var zhipuApiService: ZhipuApiService
    private var loadingDialog: AlertDialog? = null
    private lateinit var takePicture: ActivityResultLauncher<Intent>

    companion object {
        private const val REQUEST_IMAGE_CAPTURE = 1
        private const val REQUEST_CAMERA_PERMISSION = 2
        private const val IMAGE_MAX_SIZE = 800          // 最大图片尺寸
        private const val IMAGE_QUALITY = 80            // JPEG压缩质量
        private const val NETWORK_TIMEOUT = 30L         // 网络超时时间（秒）
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wardrobe)

        takePicture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // 图片拍摄成功，进行识别
                analyzeClothingImage(currentPhotoPath)
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            finish()  // 或者执行其他返回操作
        }

        initZhipuService()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "我的衣柜"

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = WardrobeAdapter(clothingItems, this)
        recyclerView.adapter = adapter

        // 设置拍照按钮点击事件
        findViewById<FloatingActionButton>(R.id.fabCamera).setOnClickListener {
            checkCameraPermissionAndTakePhoto()
        }

        loadClothingItems()
    }

    private fun showLoadingDialog(message: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_loading, null)
        dialogView.findViewById<TextView>(R.id.textViewMessage).text = message

        loadingDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        loadingDialog?.show()
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }
    private fun initZhipuService() {
        val zhipuClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .method(original.method, original.body)
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(120, TimeUnit.SECONDS)  // 增加超时时间
            .readTimeout(120, TimeUnit.SECONDS)     // 增加超时时间
            .writeTimeout(120, TimeUnit.SECONDS)    // 增加超时时间
            .retryOnConnectionFailure(true)         // 启用重试
            .build()

        val zhipuRetrofit = Retrofit.Builder()
            .baseUrl("https://open.bigmodel.cn/api/paas/v4/")
            .client(zhipuClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        zhipuApiService = zhipuRetrofit.create(ZhipuApiService::class.java)
    }

    private fun checkCameraPermissionAndTakePhoto() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        } else {
            dispatchTakePictureIntent()
        }
    }

    private fun dispatchTakePictureIntent() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            // 确保有相机应用可以处理意图
            takePictureIntent.resolveActivity(packageManager)?.also {
                // 创建图片文件
                val photoFile: File? = try {
                    createImageFile()
                } catch (ex: IOException) {
                    Toast.makeText(this, "创建图片文件失败", Toast.LENGTH_SHORT).show()
                    null
                }

                photoFile?.also {
                    val photoURI: Uri = FileProvider.getUriForFile(
                        this,
                        "com.example.smartwardrobe.fileprovider",
                        it
                    )
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    takePicture.launch(takePictureIntent)  // 使用新的 API 启动活动
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${timeStamp}_", /* prefix */
            ".jpg", /* suffix */
            storageDir /* directory */
        ).apply {
            // Save a file: path for use with ACTION_VIEW intents
            currentPhotoPath = absolutePath
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == Activity.RESULT_OK) {
            // 图片拍摄成功，进行识别
            analyzeClothingImage(currentPhotoPath)
        }
    }
    private fun createClothingItem(
        name: String?,
        style: String?,
        thickness: String?,
        category: String?,
        imageUri: String
    ): RecommendActivity.ClothingItem? {
        // 获取非空值或使用默认值
        val validName = name?.takeIf { it.isNotBlank() }
        val validStyle = style?.takeIf { it.isNotBlank() } ?: "休闲"
        val validThickness = thickness?.takeIf { it.isNotBlank() } ?: "常规"
        val validCategory = category?.takeIf { it.isNotBlank() } ?: "上衣"

        // 检查必需的名称是否存在
        return if (validName != null) {
            RecommendActivity.ClothingItem(
                name = validName,
                style = validStyle,
                thickness = validThickness,
                category = validCategory,
                imageUri = imageUri
            )
        } else {
            null
        }
    }
    private fun showErrorDialog(imagePath: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("识别失败")
            .setMessage("是否要重试识别，手动输入，或删除图片？")
            .setPositiveButton("重试") { _, _ ->
                analyzeClothingImage(imagePath)
            }
            .setNeutralButton("手动输入") { _, _ ->
                showAddClothingDialog(imagePath)
            }
            .setNegativeButton("删除") { _, _ ->
                File(imagePath).delete()
                Toast.makeText(this, "已删除图片", Toast.LENGTH_SHORT).show()
            }
            .create()
            .show()
    }

    private fun analyzeClothingImage(imagePath: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 检查文件是否存在
                val imageFile = File(imagePath)
                if (!imageFile.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WardrobeActivity, "图片文件不存在", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 压缩图片
                val compressedImage = compressImage(imageFile)
                if (compressedImage == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WardrobeActivity, "图片处理失败", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 显示加载对话框
                withContext(Dispatchers.Main) {
                    showLoadingDialog("正在识别衣物...\n请稍候")
                }

                // 将图片转换为Base64
                val base64Image = Base64.encodeToString(compressedImage, Base64.NO_WRAP)

                // 获取API令牌
                val apiKey = BuildConfig.ZHIPU_API_KEY
                val token = generateZhipuToken(apiKey) ?: run {
                    withContext(Dispatchers.Main) {
                        hideLoadingDialog()
                        Toast.makeText(this@WardrobeActivity, "API密钥无效，请检查配置", Toast.LENGTH_SHORT).show()
                        showErrorDialog(imagePath)
                    }
                    return@launch
                }

                // 优化提示词，提高识别准确度
                val prompt = """
                请作为专业的服装识别系统，严格按照以下要求识别图片中的衣物：

                重要规则：
                1. 只描述图片中实际看到的衣物
                2. 不要使用任何默认值或猜测
                3. 必须准确描述实际颜色和特征
                
                返回格式：仅返回如下JSON格式，不要添加任何其他内容
                {
                    "name": "颜色+特征+类型的具体描述",
                    "style": "风格分类",
                    "thickness": "厚度分类",
                    "category": "类别分类"
                }

                严格的选项限制：
                1. name: 必须包含准确的颜色和特征描述
                2. style: 仅限以下选项之一
                   - 休闲：日常装扮
                   - 正式：商务装扮
                   - 运动：运动装扮
                   - 时尚：潮流装扮
                3. thickness: 仅限以下选项之一
                   - 薄款：夏季/轻薄
                   - 常规：春秋季节
                   - 加厚：冬季/保暖
                4. category: 仅限以下选项之一
                   - 上衣：如T恤、衬衫
                   - 裤子：如长裤、短裤
                   - 外套：如夹克、大衣
                   - 裙子：如连衣裙
                   - 鞋子：如运动鞋
                
                示例：
                {
                    "name": "深蓝色连帽拉链运动夹克",
                    "style": "运动",
                    "thickness": "常规",
                    "category": "外套"
                }

                注意：请仔细观察图片，确保识别结果与实际图片内容完全匹配。
            """.trimIndent()

                // 构建请求
                val request = ZhipuRequest(
                    model = "glm-4v",
                    messages = listOf(
                        ZhipuMessage("user", base64Image),
                        ZhipuMessage("user", prompt)
                    ),
                    temperature = 0.1,    // 降低温度以提高准确性
                    maxTokens = 250,      // 限制输出长度
                    topP = 0.1,          // 降低采样概率
                    stream = false        // 禁用流式输出
                )

                // 使用 withTimeoutOrNull 添加超时处理
                withTimeoutOrNull(30000L) { // 30秒超时
                    try {
                        // 发起API请求
                        val response = withContext(Dispatchers.IO) {
                            zhipuApiService.getCompletion("Bearer $token", request).execute()
                        }

                        withContext(Dispatchers.Main) {
                            hideLoadingDialog()

                            if (response.isSuccessful) {
                                val result = response.body()?.choices?.firstOrNull()?.message?.content
                                try {
                                    val jsonStr = extractJsonFromResponse(result)
                                    if (jsonStr.isNotEmpty()) {
                                        val clothing = Gson().fromJson(jsonStr, RecommendActivity.ClothingItem::class.java)

                                        // 显示识别结果确认对话框
                                        AlertDialog.Builder(this@WardrobeActivity)
                                            .setTitle("识别结果确认")
                                            .setMessage("""
                                            请确认识别结果是否正确：
                                            
                                            名称：${clothing.name}
                                            风格：${clothing.style}
                                            厚度：${clothing.thickness}
                                            类别：${clothing.category}
                                        """.trimIndent())
                                            .setPositiveButton("正确") { _, _ ->
                                                createClothingItem(
                                                    clothing.name,
                                                    clothing.style,
                                                    clothing.thickness,
                                                    clothing.category,
                                                    imagePath
                                                )?.let { validClothing ->
                                                    showConfirmationDialog(validClothing, imagePath)
                                                }
                                            }
                                            .setNegativeButton("不正确") { _, _ ->
                                                // 显示手动输入对话框
                                                showAddClothingDialog(imagePath)
                                            }
                                            .setCancelable(false)
                                            .show()
                                    } else {
                                        throw IllegalStateException("Invalid JSON format")
                                    }
                                } catch (e: Exception) {
                                    Log.e("AI_Response", "解析失败: ${e.message}")
                                    Log.e("AI_Response", "原始响应: $result")
                                    // 显示错误对话框并提供手动输入选项
                                    AlertDialog.Builder(this@WardrobeActivity)
                                        .setTitle("识别失败")
                                        .setMessage("无法正确识别衣物，是否手动输入？")
                                        .setPositiveButton("手动输入") { _, _ ->
                                            showAddClothingDialog(imagePath)
                                        }
                                        .setNegativeButton("重试") { _, _ ->
                                            analyzeClothingImage(imagePath)
                                        }
                                        .setNeutralButton("取消") { _, _ ->
                                            File(imagePath).delete()
                                        }
                                        .setCancelable(false)
                                        .show()
                                }
                            } else {
                                val errorBody = response.errorBody()?.string()
                                Log.e("AI_Response", "API错误: ${response.code()} - $errorBody")
                                handleRecognitionError(imagePath, "API请求失败: ${response.code()}")
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            hideLoadingDialog()
                            Log.e("AI_Response", "网络错误", e)
                            handleRecognitionError(imagePath, "网络请求失败: ${e.message}")
                        }
                    }
                } ?: withContext(Dispatchers.Main) { // 处理超时情况
                    hideLoadingDialog()
                    handleRecognitionError(imagePath, "请求超时，请重试")
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideLoadingDialog()
                    Log.e("AI_Response", "处理失败", e)
                    handleRecognitionError(imagePath, "处理失败: ${e.message}")
                }
            }
        }
    }

    private fun validateRecognitionResult(clothing: RecommendActivity.ClothingItem): Boolean {
        // 验证 style
        val validStyles = setOf("休闲", "正式", "运动", "时尚")
        if (!validStyles.contains(clothing.style)) return false

        // 验证 thickness
        val validThickness = setOf("薄款", "常规", "加厚")
        if (!validThickness.contains(clothing.thickness)) return false

        // 验证 category
        val validCategory = setOf("上衣", "裤子", "外套", "裙子", "鞋子")
        if (!validCategory.contains(clothing.category)) return false

        // 验证 name 不为空且包含基本信息
        if (clothing.name.isBlank()) return false

        // 检查名称是否包含颜色描述
        val hasColor = clothing.name.contains(Regex("([白黑红蓝黄绿粉紫灰棕色]|米色|卡其)"))

        return hasColor
    }

    private fun handleRecognitionError(imagePath: String, errorMessage: String?) {
        AlertDialog.Builder(this)
            .setTitle("识别失败")
            .setMessage("是否要重试？\n\n错误信息：${errorMessage ?: "未知错误"}")  // 使用 Elvis 运算符提供默认值
            .setPositiveButton("重试") { _, _ ->
                analyzeClothingImage(imagePath)
            }
            .setNeutralButton("手动输入") { _, _ ->
                showAddClothingDialog(imagePath)
            }
            .setNegativeButton("取消") { _, _ ->
                File(imagePath).delete()
            }
            .setCancelable(false)
            .show()
    }

    private fun extractJsonFromResponse(response: String?): String {
        if (response.isNullOrBlank()) return ""

        try {
            // 首先尝试直接解析
            if (response.trim().startsWith("{") && response.trim().endsWith("}")) {
                return response.trim()
            }

            // 使用正则表达式匹配JSON对象
            val jsonRegex = """\{(?:[^{}]|("(?:[^"\\]|\\.)*"))*\}""".toRegex()
            val match = jsonRegex.find(response)

            return match?.value ?: ""
        } catch (e: Exception) {
            Log.e("AI_Response", "JSON提取失败", e)
            return ""
        }
    }

    private fun compressImage(imageFile: File): ByteArray? {
        return try {
            // 获取图片的采样率以减少内存使用
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(imageFile.absolutePath, options)

            val sampleSize = calculateInSampleSize(options, IMAGE_MAX_SIZE, IMAGE_MAX_SIZE)
            options.apply {
                inJustDecodeBounds = false
                inSampleSize = sampleSize
            }

            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options)

            // 如果需要，进一步缩放图片
            val scaledBitmap = if (bitmap.width > IMAGE_MAX_SIZE || bitmap.height > IMAGE_MAX_SIZE) {
                val ratio = Math.min(
                    IMAGE_MAX_SIZE.toFloat() / bitmap.width,
                    IMAGE_MAX_SIZE.toFloat() / bitmap.height
                )
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt(),
                    (bitmap.height * ratio).toInt(),
                    true
                )
            } else {
                bitmap
            }

            ByteArrayOutputStream().use { stream ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, stream)
                stream.toByteArray()
            }
        } catch (e: Exception) {
            Log.e("ImageCompression", "压缩图片失败", e)
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
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

    private fun validateClothingItem(clothing: RecommendActivity.ClothingItem): Boolean {
        val isValid = clothing.name.isNotBlank() &&
                clothing.style.isNotBlank() &&
                clothing.thickness.isNotBlank() &&
                clothing.category.isNotBlank()

        if (!isValid) {
            Log.e("AI_Response", """
            Validation failed:
            name: ${clothing.name}
            style: ${clothing.style}
            thickness: ${clothing.thickness}
            category: ${clothing.category}
        """.trimIndent())
        }

        return isValid
    }

    private fun getPrompt(): String {
        return """
        分析这张衣物图片，直接返回以下JSON格式，不要添加任何其他内容：
        {
            "name": "白色短袖T恤",
            "style": "休闲",
            "thickness": "薄款",
            "category": "上衣"
        }
        注意：
        1. style必须是：休闲/正式/运动/时尚之一
        2. thickness必须是：薄款/常规/加厚之一
        3. category必须是：上衣/裤子/外套/裙子/鞋子之一
        4. 只返回JSON，不要包含其他文字
    """.trimIndent()
    }

    private fun showConfirmationDialog(clothing: RecommendActivity.ClothingItem, imagePath: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_clothing, null)

        // 获取输入框和下拉选择器引用
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etName)
        val spinnerStyle = dialogView.findViewById<Spinner>(R.id.spinnerStyle)
        val spinnerThickness = dialogView.findViewById<Spinner>(R.id.spinnerThickness)
        val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinnerCategory)

        // 设置识别结果到输入框
        etName.setText(clothing.name)

        // 设置风格选择器
        val styles = arrayOf("休闲", "正式", "运动", "时尚")
        val styleAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, styles)
        styleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerStyle.adapter = styleAdapter
        spinnerStyle.setSelection(styles.indexOf(clothing.style).coerceAtLeast(0))

        // 设置厚度选择器
        val thicknesses = arrayOf("薄款", "常规", "加厚")
        val thicknessAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, thicknesses)
        thicknessAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerThickness.adapter = thicknessAdapter
        spinnerThickness.setSelection(thicknesses.indexOf(clothing.thickness).coerceAtLeast(0))

        // 设置类别选择器
        val categories = arrayOf("上衣", "裤子", "外套", "裙子", "鞋子")
        val categoryAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategory.adapter = categoryAdapter
        spinnerCategory.setSelection(categories.indexOf(clothing.category).coerceAtLeast(0))

        AlertDialog.Builder(this)
            .setTitle("确认衣物信息")
            .setView(dialogView)
            .setPositiveButton("确认") { _, _ ->
                val name = etName.text?.toString()
                val style = spinnerStyle.selectedItem?.toString()
                val thickness = spinnerThickness.selectedItem?.toString()
                val category = spinnerCategory.selectedItem?.toString()

                createClothingItem(name, style, thickness, category, imagePath)?.let { item ->
                    clothingItems.add(item)
                    adapter.notifyItemInserted(clothingItems.size - 1)
                    saveItems()
                    Toast.makeText(this, "已添加新衣物", Toast.LENGTH_SHORT).show()
                } ?: run {
                    Toast.makeText(this, "请输入衣物名称", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                File(imagePath).delete()
            }
            .create()
            .show()
    }

    private fun generateZhipuToken(apiKey: String): String? {
        try {
            val parts = apiKey.trim().split(".")
            if (parts.size != 2) return null
            val id = parts[0]
            val secret = parts[1]
            if (id.isEmpty() || secret.isEmpty()) return null

            val algorithm = Algorithm.HMAC256(secret)
            val now = System.currentTimeMillis()
            val headerClaims = mapOf(
                "alg" to "HS256",
                "sign_type" to "SIGN"
            )

            return JWT.create()
                .withHeader(headerClaims)
                .withClaim("api_key", id)
                .withClaim("exp", now + 3600 * 1000)
                .withClaim("timestamp", now)
                .sign(algorithm)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun showAddClothingDialog(imagePath: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_clothing, null)

        // 获取输入框和下拉选择器引用
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etName)
        val spinnerStyle = dialogView.findViewById<Spinner>(R.id.spinnerStyle)
        val spinnerThickness = dialogView.findViewById<Spinner>(R.id.spinnerThickness)
        val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinnerCategory)

        // 设置风格选择器
        val styleAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            arrayOf("休闲", "正式", "运动", "时尚")
        )
        styleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerStyle.adapter = styleAdapter

        // 设置厚度选择器
        val thicknessAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            arrayOf("薄款", "常规", "加厚")
        )
        thicknessAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerThickness.adapter = thicknessAdapter

        // 设置类别选择器
        val categoryAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            arrayOf("上衣", "裤子", "外套", "裙子", "鞋子")
        )
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategory.adapter = categoryAdapter

        AlertDialog.Builder(this)
            .setTitle("添加衣物")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text?.toString()
                val style = spinnerStyle.selectedItem?.toString()
                val thickness = spinnerThickness.selectedItem?.toString()
                val category = spinnerCategory.selectedItem?.toString()

                createClothingItem(name, style, thickness, category, imagePath)?.let { item ->
                    clothingItems.add(item)
                    adapter.notifyItemInserted(clothingItems.size - 1)
                    saveItems()
                    Toast.makeText(this, "已添加新衣物", Toast.LENGTH_SHORT).show()
                } ?: run {
                    Toast.makeText(this, "请输入衣物名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton  // 如果创建失败，不关闭对话框
                }
            }
            .setNegativeButton("取消") { _, _ ->
                File(imagePath).delete()
            }
            .create()
            .show()
    }

    private fun saveItems() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(Constants.KEY_ITEMS, Gson().toJson(clothingItems)).apply()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                dispatchTakePictureIntent()
            } else {
                Toast.makeText(this, "需要相机权限来拍照", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadClothingItems() {
        try {
            // 首先尝试从 SharedPreferences 加载数据
            val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            val savedItems = prefs.getString(Constants.KEY_ITEMS, null)

            if (savedItems != null) {
                // 如果有保存的数据，使用保存的数据
                val type = object : TypeToken<List<RecommendActivity.ClothingItem>>() {}.type
                clothingItems = Gson().fromJson(savedItems, type)
            } else {
                // 如果没有保存的数据，加载默认数据
                val jsonString = assets.open(Constants.DEFAULT_JSON).bufferedReader().use { it.readText() }
                val type = object : TypeToken<List<RecommendActivity.ClothingItem>>() {}.type
                clothingItems = Gson().fromJson<List<RecommendActivity.ClothingItem>>(jsonString, type).toMutableList()

                // 保存默认数据到 SharedPreferences
                prefs.edit().putString(Constants.KEY_ITEMS, Gson().toJson(clothingItems)).apply()
            }

            adapter = WardrobeAdapter(clothingItems, this)
            recyclerView.adapter = adapter

        } catch (e: Exception) {
            Toast.makeText(this, "加载衣物数据失败", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return true
    }


}