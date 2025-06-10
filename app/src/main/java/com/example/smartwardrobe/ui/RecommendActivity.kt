package com.example.smartwardrobe.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView // 导入 ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.smartwardrobe.BuildConfig
import com.example.smartwardrobe.R
import com.example.smartwardrobe.data.*
import com.example.smartwardrobe.logic.OutfitRecommender
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.Locale // 导入 Locale

// Zhipu AI的数据类和接口定义保持不变...
data class ZhipuRequest(
    val model: String = "glm-4",
    val messages: List<ZhipuMessage>
)
data class ZhipuMessage(
    val role: String = "user",
    val content: String
)
data class ZhipuResponse(
    val choices: List<ZhipuChoice>,
    val usage: ZhipuUsage
)
data class ZhipuChoice(
    val message: ZhipuMessage
)
data class ZhipuUsage(
    @SerializedName("total_tokens")
    val totalTokens: Int
)
interface ZhipuApiService {
    @POST("chat/completions")
    fun getCompletion(
        @Header("Authorization") token: String,
        @Body request: ZhipuRequest
    ): Call<ZhipuResponse>
}


class RecommendActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 1001
        private const val PREFS_NAME = "clothing_prefs"
        private const val KEY_ITEMS = "clothing_items"
        private const val DEFAULT_JSON = "default_clothing.json"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val weatherApiBaseUrl = "https://api.openweathermap.org/data/2.5/"

    private lateinit var zhipuApiService: ZhipuApiService
    private lateinit var weatherService: WeatherService

    // --- [修改] 声明新的天气卡片UI组件 ---
    private lateinit var ivWeatherBackground: ImageView
    private lateinit var ivWeatherIcon: ImageView
    private lateinit var tvLocation: TextView
    private lateinit var tvTemperature: TextView
    private lateinit var tvWeatherCondition: TextView

    // --- 其他UI组件声明 ---
    private lateinit var etHuggingFaceInput: EditText
    private lateinit var btnSubmitToHuggingFace: Button
    private lateinit var tvRecommendations: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRetry: Button

    // 用于存储当前天气信息的变量
    private var currentWeatherData: WeatherResponse? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recommend)

        initViews()
        initServices()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        loadInitialClothing()
        requestLocationAndLoadWeather()

        btnSubmitToHuggingFace.setOnClickListener {
            val userInput = etHuggingFaceInput.text.toString().trim()
            if (userInput.isNotEmpty()) {
                fetchZhipuRecommendation(userInput)
            } else {
                Toast.makeText(this, "请输入你的想法", Toast.LENGTH_SHORT).show()
            }
        }

        btnRetry.setOnClickListener {
            requestLocationAndLoadWeather()
        }
    }

    // --- [修改] 初始化所有新的UI视图 ---
    private fun initViews() {
        // 天气卡片视图
        ivWeatherBackground = findViewById(R.id.iv_weather_background)
        ivWeatherIcon = findViewById(R.id.iv_weather_icon)
        tvLocation = findViewById(R.id.tv_location)
        tvTemperature = findViewById(R.id.tv_temperature)
        tvWeatherCondition = findViewById(R.id.tv_weather_condition)

        // 其他视图
        etHuggingFaceInput = findViewById(R.id.etHuggingFaceInput)
        btnSubmitToHuggingFace = findViewById(R.id.btnSubmitToHuggingFace)
        tvRecommendations = findViewById(R.id.tvRecommendations)
        progressBar = findViewById(R.id.progressBar)
        btnRetry = findViewById(R.id.btnRetry)
    }

    // initServices() 和 generateZhipuToken() 保持不变...
    private fun initServices() {
        weatherService = Retrofit.Builder()
            .baseUrl(weatherApiBaseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherService::class.java)

        val zhipuRetrofit = Retrofit.Builder()
            .baseUrl("https://open.bigmodel.cn/api/paas/v4/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        zhipuApiService = zhipuRetrofit.create(ZhipuApiService::class.java)
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
            val headerClaims = mapOf("alg" to "HS256", "sign_type" to "SIGN")
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


    // [修改] fetchZhipuRecommendation 函数，使其使用已经保存的天气数据
    private fun fetchZhipuRecommendation(userInput: String) {
        val apiKey = BuildConfig.ZHIPU_API_KEY
        if (apiKey.isEmpty() || apiKey == "YOUR_ZHIPU_API_KEY") {
            Toast.makeText(this, "API Key未在local.properties中配置", Toast.LENGTH_LONG).show()
            return
        }
        val token = generateZhipuToken(apiKey)
        if (token == null) {
            Toast.makeText(this, "API Key格式错误，无法生成Token", Toast.LENGTH_LONG).show()
            return
        }

        // 检查是否有天气数据
        if (currentWeatherData == null) {
            Toast.makeText(this, "正在等待天气信息...", Toast.LENGTH_SHORT).show()
            return
        }

        val mockWardrobe = listOf(
            ClothingItem(name = "基础款白T恤", category = "T恤", thickness = "薄", style = "休闲"),
            ClothingItem(name = "纯黑圆领T恤", category = "T恤", thickness = "薄", style = "休闲"),
            ClothingItem(name = "经典蓝色Polo衫", category = "Polo衫", thickness = "薄" , style = "商务休闲"),
            ClothingItem(name = "修身牛仔裤", category = "牛仔裤",  thickness = "中",style = "休闲"),
            ClothingItem(name = "卡其色休闲裤", category = "休闲裤", thickness = "厚", style = "休闲"),
            ClothingItem(name = "深灰色西裤", category = "西裤",  thickness = "厚", style = "商务"),
            ClothingItem(name = "黑色西装外套", category = "西装外套", thickness = "厚", style = "商务"),
            ClothingItem(name = "小白鞋", category = "运动鞋", thickness = "中", style = "休闲"),
            ClothingItem(name = "黑色商务皮鞋", category = "皮鞋", thickness = "厚", style = "商务")
        )

        // 从天气卡片UI获取天气信息文本
        val weatherInfo = "地点: ${tvLocation.text}, 天气: ${tvWeatherCondition.text}, 温度: ${tvTemperature.text}"

        val wardrobeJson = Gson().toJson(mockWardrobe)

        val prompt = """
    你是一位专业的穿搭顾问。请根据以下全部信息，为我推荐一套最合适的穿搭方案。

    ---
    **我的需求或场景：**
    $userInput

    ---
    **当前天气情况：**
    $weatherInfo

    ---
    **我的衣橱里所有可用的衣物清单（请严格遵守并只从以下清单中选择衣物进行搭配）：**
    $wardrobeJson
    ---

    请直接以“上装: [衣物], 下装: [衣物], 外套(可选): [衣物], 鞋子: [衣物]”的格式给出你的推荐，不要说其他无关的话。
    """.trimIndent()

        Log.d("SuperPrompt", "构造出的提示词是:\n$prompt")

        progressBar.visibility = View.VISIBLE
        tvRecommendations.text = "AI正在根据你的衣橱和天气进行搭配..."

        val messages = listOf(ZhipuMessage(content = prompt))
        val request = ZhipuRequest(messages = messages)
        val call = zhipuApiService.getCompletion("Bearer $token", request)

        call.enqueue(object : Callback<ZhipuResponse> {
            override fun onResponse(call: Call<ZhipuResponse>, response: Response<ZhipuResponse>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful) {
                    val result = response.body()?.choices?.firstOrNull()?.message?.content
                    tvRecommendations.text = result ?: "AI没有返回有效的建议。"
                    Toast.makeText(this@RecommendActivity, "智能衣橱建议获取成功！", Toast.LENGTH_SHORT).show()
                } else {
                    val errorBody = response.errorBody()?.string()
                    tvRecommendations.text = "请求失败，错误码：${response.code()}\n${errorBody}"
                    Log.e("ZhipuAPI", "Error: ${response.code()} - $errorBody")
                }
            }

            override fun onFailure(call: Call<ZhipuResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                tvRecommendations.text = "网络请求失败，请检查网络连接。\n错误: ${t.message}"
                Log.e("ZhipuAPI", "Failure: ${t.message}", t)
            }
        })
    }

    // loadInitialClothing(), requestLocationAndLoadWeather(), onRequestPermissionsResult() 保持不变...
    private fun loadInitialClothing() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_ITEMS)) {
            val defaultList = loadDefaultClothingFromAssets()
            if (defaultList.isNotEmpty()) {
                saveUserItemsToLocal(defaultList)
            }
        }
    }
    private fun requestLocationAndLoadWeather() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION_PERMISSION)
        } else {
            fetchLocationAndLoadWeather()
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchLocationAndLoadWeather()
            } else {
                Toast.makeText(this, "未获取定位权限，将使用默认城市", Toast.LENGTH_LONG).show()
                fetchWeatherByCity("北京")
            }
        }
    }

    // fetchLocationAndLoadWeather(), fetchWeatherByCoordinates(), fetchWeatherByCity() 保持不变...
    private fun fetchLocationAndLoadWeather() {
        progressBar.visibility = View.VISIBLE
        btnRetry.visibility = View.GONE

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { return }
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    fetchWeatherByCoordinates(location.latitude, location.longitude)
                } else {
                    fetchWeatherByCity("北京")
                }
            }
            .addOnFailureListener {
                progressBar.visibility = View.GONE
                btnRetry.visibility = View.VISIBLE
                fetchWeatherByCity("北京")
            }
    }
    private fun fetchWeatherByCoordinates(lat: Double, lon: Double) {
        val apiKey = BuildConfig.WEATHER_API_KEY
        val call = weatherService.getCurrentWeatherByCoordinates(lat, lon, apiKey)
        call.enqueue(object : Callback<WeatherResponse> {
            override fun onResponse(call: Call<WeatherResponse>, response: Response<WeatherResponse>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful) {
                    currentWeatherData = response.body()
                    displayRecommendations(currentWeatherData)
                } else {
                    btnRetry.visibility = View.VISIBLE
                    Toast.makeText(this@RecommendActivity, "获取天气信息失败: ${response.message()}", Toast.LENGTH_LONG).show()
                }
            }
            override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                btnRetry.visibility = View.VISIBLE
                Toast.makeText(this@RecommendActivity, "获取天气失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }
    private fun fetchWeatherByCity(cityName: String) {
        val apiKey = BuildConfig.WEATHER_API_KEY
        val call = weatherService.getCurrentWeatherByCity(cityName, apiKey)
        call.enqueue(object : Callback<WeatherResponse> {
            override fun onResponse(call: Call<WeatherResponse>, response: Response<WeatherResponse>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful) {
                    currentWeatherData = response.body()
                    displayRecommendations(currentWeatherData)
                } else {
                    btnRetry.visibility = View.VISIBLE
                    Toast.makeText(this@RecommendActivity, "获取天气信息失败: ${response.message()}", Toast.LENGTH_LONG).show()
                }
            }
            override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                btnRetry.visibility = View.VISIBLE
                Toast.makeText(this@RecommendActivity, "获取天气失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }


    // --- [新增] 更新天气卡片UI的专用方法 ---
    private fun updateWeatherUI(weather: WeatherResponse?) {
        if (weather == null) {
            // 可以设置一个默认的或者错误的UI状态
            tvLocation.text = "未知地点"
            tvTemperature.text = "--°"
            tvWeatherCondition.text = "天气获取失败"
            ivWeatherBackground.setImageResource(R.drawable.bg_default) // 确保你有一个名为 bg_default 的默认背景图
            ivWeatherIcon.setImageResource(R.drawable.ic_default)       // 确保你有一个名为 ic_default 的默认图标
            return
        }

        tvLocation.text = weather.name
        tvTemperature.text = String.format(Locale.getDefault(), "%.0f°", weather.main.temp)

        val condition = weather.weather.firstOrNull()?.main ?: "Unknown"
        tvWeatherCondition.text = condition

        // 根据天气状况设置背景和图标
        when (condition.toLowerCase(Locale.ROOT)) {
            "clear" -> {
                ivWeatherBackground.setImageResource(R.drawable.bg_sunny)
                ivWeatherIcon.setImageResource(R.drawable.ic_sunny)
            }
            "clouds" -> {
                ivWeatherBackground.setImageResource(R.drawable.bg_cloudy)
                ivWeatherIcon.setImageResource(R.drawable.ic_cloudy)
            }
            "rain", "drizzle", "thunderstorm" -> {
                ivWeatherBackground.setImageResource(R.drawable.bg_rainy)
                ivWeatherIcon.setImageResource(R.drawable.ic_rainy)
            }
            "snow" -> {
                ivWeatherBackground.setImageResource(R.drawable.bg_snow) // 你需要准备 bg_snow 和 ic_snow
                ivWeatherIcon.setImageResource(R.drawable.ic_snow)
            }
            else -> {
                ivWeatherBackground.setImageResource(R.drawable.bg_default)
                ivWeatherIcon.setImageResource(R.drawable.ic_default)
            }
        }
    }


    // --- [修改] displayRecommendations 现在只负责显示推荐，天气UI更新交给新方法 ---
    private fun displayRecommendations(weather: WeatherResponse?) {
        // 步骤1：更新天气UI（无论是否成功）
        updateWeatherUI(weather)

        // 步骤2：如果天气数据获取失败，则不进行后续的衣物推荐
        if (weather == null) {
            tvRecommendations.text = "无法获取天气，暂时不能提供穿搭建议。"
            return
        }

        // 步骤3：继续进行原有的衣物推荐逻辑
        val occasion = intent.getStringExtra("occasion") ?: ""
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ITEMS, null)
        val userItems: List<ClothingItem> = if (json.isNullOrEmpty()) emptyList() else {
            val type = object : TypeToken<List<ClothingItem>>() {}.type
            Gson().fromJson(json, type)
        }
        if (userItems.isEmpty()) {
            tvRecommendations.text = "你的衣橱是空的，请先添加衣物。"
            return
        }

        val recommendedOutfits = OutfitRecommender.recommend(weather, UserPreferences(this).getUserInfo(), userItems, occasion)
        val sb = StringBuilder()
        recommendedOutfits.forEachIndexed { index, outfit ->
            sb.append("—— 推荐套装 ${index + 1}（得分 ${"%.1f".format(outfit.score)}）——\n")
            sb.append("上装: ${outfit.top?.name ?: "无"}\n")
            sb.append("下装: ${outfit.bottom?.name ?: "无"}\n")
            sb.append("外套: ${outfit.outer?.name ?: "无"}\n")
            sb.append("鞋子: ${outfit.shoes?.name ?: "无"}\n\n")
        }

        // 如果tvRecommendations是默认文本，才更新它
        if (tvRecommendations.text.contains("将会显示在这里") || tvRecommendations.text.isEmpty()) {
            tvRecommendations.text = if (sb.isNotEmpty()) sb.toString() else "没有找到合适的搭配"
        }
    }

    // loadDefaultClothingFromAssets() 和 saveUserItemsToLocal() 保持不变...
    private fun loadDefaultClothingFromAssets(): List<ClothingItem> {
        return try {
            assets.open(DEFAULT_JSON).bufferedReader(Charsets.UTF_8).use {
                val jsonStr = it.readText()
                val type = object : TypeToken<List<ClothingItem>>() {}.type
                Gson().fromJson(jsonStr, type)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    private fun saveUserItemsToLocal(items: List<ClothingItem>) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ITEMS, Gson().toJson(items)).apply()
    }
}