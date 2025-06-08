package com.example.smartwardrobe.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.smartwardrobe.BuildConfig // 确认这个import存在
import com.example.smartwardrobe.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.example.smartwardrobe.data.*
import com.example.smartwardrobe.logic.OutfitRecommender
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.InputStreamReader
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

// ... Zhipu AI的数据类和接口定义保持不变 ...
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

    // UI 组件声明
    private lateinit var etHuggingFaceInput: EditText
    private lateinit var btnSubmitToHuggingFace: Button
    private lateinit var tvRecommendations: TextView
    private lateinit var tvWeather: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRetry: Button // <-- [修正] 在这里声明

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recommend)

        initViews()
        initServices()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        loadInitialClothing()
        requestLocationAndLoadWeather()

        // AI建议按钮的点击事件
        btnSubmitToHuggingFace.setOnClickListener {
            val userInput = etHuggingFaceInput.text.toString().trim()
            if (userInput.isNotEmpty()) {
                fetchZhipuRecommendation(userInput)
            } else {
                Toast.makeText(this, "请输入你的想法", Toast.LENGTH_SHORT).show()
            }
        }

        // [修正] 重试按钮的点击事件
        btnRetry.setOnClickListener {
            requestLocationAndLoadWeather() // 点击后，重新执行获取天气和推荐的流程
        }
    }

    private fun initViews() {
        etHuggingFaceInput = findViewById(R.id.etHuggingFaceInput)
        btnSubmitToHuggingFace = findViewById(R.id.btnSubmitToHuggingFace)
        tvRecommendations = findViewById(R.id.tvRecommendations)
        tvWeather = findViewById(R.id.tvWeather)
        progressBar = findViewById(R.id.progressBar)
        btnRetry = findViewById(R.id.btnRetry) // <-- [修正] 在这里初始化
    }

    // ... initServices() 和 generateZhipuToken() 保持不变 ...
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
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "API Key未在local.properties中配置", Toast.LENGTH_LONG).show()
            return
        }
        val token = generateZhipuToken(apiKey)
        if (token == null) {
            Toast.makeText(this, "API Key格式错误，无法生成Token", Toast.LENGTH_LONG).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        tvRecommendations.text = "智谱AI正在思考中..."
        val messages = listOf(ZhipuMessage(content = "请帮我提供一套适合以下场景的穿搭建议：${userInput}。请直接给出具体的衣物搭配，不要说其他无关的话。"))
        val request = ZhipuRequest(messages = messages)
        val call = zhipuApiService.getCompletion("Bearer $token", request)

        call.enqueue(object : Callback<ZhipuResponse> {
            override fun onResponse(call: Call<ZhipuResponse>, response: Response<ZhipuResponse>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful) {
                    val result = response.body()?.choices?.firstOrNull()?.message?.content
                    tvRecommendations.text = result ?: "AI没有返回有效的建议。"
                    Toast.makeText(this@RecommendActivity, "智谱AI建议获取成功！", Toast.LENGTH_SHORT).show()
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

    // ... loadInitialClothing() 和 requestLocationAndLoadWeather() 保持不变 ...
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

    // [修正] 在天气获取流程中加入重试按钮的UI控制
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
                displayRecommendations(response.body())
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
                displayRecommendations(response.body())
            }
            override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                btnRetry.visibility = View.VISIBLE
                Toast.makeText(this@RecommendActivity, "获取天气失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    // ... displayRecommendations(), loadDefaultClothingFromAssets(), saveUserItemsToLocal() 保持不变 ...
    private fun displayRecommendations(weather: WeatherResponse?) {
        val occasion = intent.getStringExtra("occasion") ?: ""
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ITEMS, null)
        val userItems: List<ClothingItem> = if (json.isNullOrEmpty()) emptyList() else {
            val type = object : TypeToken<List<ClothingItem>>() {}.type
            Gson().fromJson(json, type)
        }
        if (userItems.isEmpty()) { return }
        val recommendedOutfits = OutfitRecommender.recommend(weather, UserPreferences(this).getUserInfo(), userItems, occasion)
        val sb = StringBuilder()
        recommendedOutfits.forEachIndexed { index, outfit ->
            sb.append("—— 推荐套装 ${index + 1}（得分 ${"%.1f".format(outfit.score)}）——\n")
            sb.append("上装: ${outfit.top?.name ?: "无"}\n")
            sb.append("下装: ${outfit.bottom?.name ?: "无"}\n")
            sb.append("外套: ${outfit.outer?.name ?: "无"}\n")
            sb.append("鞋子: ${outfit.shoes?.name ?: "无"}\n\n")
        }
        tvWeather.text = "天气: ${weather?.weather?.get(0)?.main ?: "未知"}，温度: ${"%.1f".format(weather?.main?.temp ?: 0.0)} °C，场合: $occasion"
        if (tvRecommendations.text.contains("将会显示在这里") || tvRecommendations.text.isEmpty()) {
            tvRecommendations.text = if (sb.isNotEmpty()) sb.toString() else "没有找到合适的搭配"
        }
    }
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