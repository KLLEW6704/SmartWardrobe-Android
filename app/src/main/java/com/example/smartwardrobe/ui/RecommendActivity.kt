package com.example.smartwardrobe.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.smartwardrobe.BuildConfig
import com.example.smartwardrobe.R
import com.example.smartwardrobe.data.*
import com.example.smartwardrobe.logic.OutfitRecommender
import com.example.smartwardrobe.logic.ZhipuPromptHelper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.Locale

// Zhipu AI的数据类和接口定义
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

    private lateinit var ivWeatherBackground: ImageView
    private lateinit var ivWeatherIcon: ImageView
    private lateinit var tvLocation: TextView
    private lateinit var tvTemperature: TextView
    private lateinit var tvWeatherCondition: TextView

    private lateinit var etHuggingFaceInput: EditText
    private lateinit var btnSubmitToHuggingFace: Button
    private lateinit var tvRecommendations: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRetry: Button

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

    private fun initViews() {
        ivWeatherBackground = findViewById(R.id.iv_weather_background)
        ivWeatherIcon = findViewById(R.id.iv_weather_icon)
        tvLocation = findViewById(R.id.tv_location)
        tvTemperature = findViewById(R.id.tv_temperature)
        tvWeatherCondition = findViewById(R.id.tv_weather_condition)
        etHuggingFaceInput = findViewById(R.id.etHuggingFaceInput)
        btnSubmitToHuggingFace = findViewById(R.id.btnSubmitToHuggingFace)
        tvRecommendations = findViewById(R.id.tvRecommendations)
        progressBar = findViewById(R.id.progressBar)
        btnRetry = findViewById(R.id.btnRetry)
    }

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
        if (currentWeatherData == null) {
            Toast.makeText(this, "正在等待天气信息...", Toast.LENGTH_SHORT).show()
            return
        }

        // 获取用户衣柜和用户信息
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val itemsJson = prefs.getString(KEY_ITEMS, null)
        val userItems: List<ClothingItem> = if (itemsJson.isNullOrEmpty()) emptyList() else {
            val type = object : TypeToken<List<ClothingItem>>() {}.type
            Gson().fromJson(itemsJson, type)
        }
        val wardrobeJson = Gson().toJson(userItems)
        val userInfo = UserPreferences(this).getUserInfo()
        val occasion = userInput

        // 拼接优化后的prompt
        val prompt = ZhipuPromptHelper.buildZhipuOutfitPrompt(
            gender = userInfo.gender,
            bodyType = userInfo.comfort,
            city = currentWeatherData!!.name,
            weatherDesc = currentWeatherData!!.weather.firstOrNull()?.main ?: "未知",
            temperature = currentWeatherData!!.main.temp.toString(),
            occasion = occasion,
            wardrobeJson = wardrobeJson
        )

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

    private fun updateWeatherUI(weather: WeatherResponse?) {
        if (weather == null) {
            tvLocation.text = "未知地点"
            tvTemperature.text = "--°"
            tvWeatherCondition.text = "天气获取失败"
            ivWeatherBackground.setImageResource(R.drawable.bg_default)
            ivWeatherIcon.setImageResource(R.drawable.ic_default)
            return
        }

        tvLocation.text = weather.name
        tvTemperature.text = String.format(Locale.getDefault(), "%.0f°", weather.main.temp)
        val condition = weather.weather.firstOrNull()?.main ?: "Unknown"
        tvWeatherCondition.text = condition

        when (condition.lowercase(Locale.ROOT)) {
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
                ivWeatherBackground.setImageResource(R.drawable.bg_snow)
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

        // 步骤2：如果天气数据获取失败，则提示用户
        if (weather == null) {
            tvRecommendations.text = "无法获取天气，暂时不能提供穿搭建议。"
            return
        }

        // 步骤3：显示提示信息，引导用户使用AI推荐
        tvRecommendations.text = "请输入你的场景或需求，获取智能穿搭建议"
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