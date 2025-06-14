package com.example.smartwardrobe.ui

import java.util.concurrent.TimeUnit
import android.Manifest
import android.content.Context
import android.content.Intent
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.Locale


data class ZhipuMessage(
    val role: String = "user",
    val content: String
)



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
    private var useDefaultMode = false
    private var defaultClothingItems: List<ClothingItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recommend)

        initViews()
        initServices()
        loadDefaultClothing()
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

        val spinner = findViewById<Spinner>(R.id.wardrobeSpinner)
        ArrayAdapter.createFromResource(
            this,
            R.array.wardrobe_types,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                useDefaultMode = pos == 1
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                useDefaultMode = false
            }
        }

        val fabWardrobe = findViewById<FloatingActionButton>(R.id.fabWardrobe)
        fabWardrobe.setOnClickListener {
            val intent = Intent(this, WardrobeActivity::class.java)
            startActivity(intent)
        }
    }

    private fun initServices() {
        // 天气服务
        val weatherClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("Accept", "application/json")
                    .method(original.method, original.body)
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)  // 连接超时时间
            .readTimeout(30, TimeUnit.SECONDS)     // 读取超时时间
            .writeTimeout(30, TimeUnit.SECONDS)    // 写入超时时间
            .build()

        weatherService = Retrofit.Builder()
            .baseUrl(weatherApiBaseUrl)
            .client(weatherClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherService::class.java)

        // 智谱 AI 服务
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
            .connectTimeout(60, TimeUnit.SECONDS)  // 增加连接超时时间
            .readTimeout(60, TimeUnit.SECONDS)     // 增加读取超时时间
            .writeTimeout(60, TimeUnit.SECONDS)    // 增加写入超时时间
            .retryOnConnectionFailure(true)        // 启用重试机制
            .build()

        val zhipuRetrofit = Retrofit.Builder()
            .baseUrl("https://open.bigmodel.cn/api/paas/v4/")
            .client(zhipuClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        zhipuApiService = zhipuRetrofit.create(ZhipuApiService::class.java)
    }

    private fun loadDefaultClothing() {
        try {
            val jsonString = assets.open(DEFAULT_JSON).bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<ClothingItem>>() {}.type
            val items: List<ClothingItem> = Gson().fromJson(jsonString, type) ?: emptyList()
            defaultClothingItems = if (items.isEmpty()) {
                Log.w("RecommendActivity", "No items found in $DEFAULT_JSON")
                emptyList()
            } else {
                items
            }
        } catch (e: Exception) {
            Log.e("RecommendActivity", "Error loading default clothing", e)
            defaultClothingItems = emptyList()
            Toast.makeText(
                this,
                "无法加载默认衣物数据，将使用空列表",
                Toast.LENGTH_SHORT
            ).show()
        }
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

    private fun fetchZhipuRecommendation(userInput: String) {
        val apiKey = BuildConfig.ZHIPU_API_KEY
        if (apiKey.isEmpty() || apiKey == "YOUR_ZHIPU_API_KEY") {
            Toast.makeText(this, "API Key未在local.properties中配置", Toast.LENGTH_LONG).show()
            return
        }

        Log.d("ZhipuAPI", "Starting API request...")

        val token = generateZhipuToken(apiKey)
        if (token == null) {
            Toast.makeText(this, "API Key格式错误，无法生成Token", Toast.LENGTH_LONG).show()
            return
        }

        Log.d("ZhipuAPI", "Generated token: ${token.take(20)}...")

        if (currentWeatherData == null) {
            Toast.makeText(this, "正在等待天气信息...", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        btnRetry.visibility = View.GONE
        tvRecommendations.text = "AI正在根据你的需求进行搭配..."

        val promptText = if (useDefaultMode || defaultClothingItems.isEmpty()) {
            // 如果是默认模式或者衣柜为空，使用默认提示
            buildDefaultPrompt(userInput)
        } else {
            // 使用我的衣柜模式
            buildWardrobePrompt(userInput)
        }

        Log.d("ZhipuAPI", "Final prompt: ${promptText.take(200)}...")

        val request = ZhipuRequest(
            model = "glm-4",
            messages = listOf(ZhipuMessage(role = "user", content = promptText)),
            temperature = 0.7,
            maxTokens = 2048,
        )

        val call = zhipuApiService.getCompletion("Bearer $token", request)

        call.enqueue(object : Callback<ZhipuResponse> {
            override fun onResponse(call: Call<ZhipuResponse>, response: Response<ZhipuResponse>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful) {
                    val result = response.body()?.choices?.firstOrNull()?.message?.content
                    tvRecommendations.text = result ?: "AI没有返回有效的建议。"
                    Log.d("ZhipuAPI", "Successful response: ${result?.take(100)}...")
                    Toast.makeText(this@RecommendActivity, "智能衣橱建议获取成功！", Toast.LENGTH_SHORT).show()
                } else {
                    val errorBody = response.errorBody()?.string()
                    tvRecommendations.text = "请求失败，错误码：${response.code()}\n${errorBody}"
                    Log.e("ZhipuAPI", "Error response: ${response.code()} - $errorBody")
                }
            }

            override fun onFailure(call: Call<ZhipuResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                val errorMessage = "网络请求失败：${t.message}"
                tvRecommendations.text = errorMessage
                Log.e("ZhipuAPI", "Network failure", t)
                Toast.makeText(this@RecommendActivity, errorMessage, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun buildDefaultPrompt(userInput: String): String {
        val temperature = currentWeatherData?.main?.temp?.let { "${it}°C" } ?: "未知温度"
        val weatherDesc = currentWeatherData?.weather?.firstOrNull()?.main ?: "未知天气"

        val userInfo = UserPreferences(this).getUserInfo()
        val userGender = userInfo.gender

        return """
        请为${userGender}性用户提供简短的穿搭建议：

        场景：$userInput
        天气：$temperature, $weatherDesc
        
        请简洁列出：
        1. 搭配方案（上衣、外套、下装、鞋子）
        2. 一句话说明原因
    """.trimIndent()
    }

    private fun buildWardrobePrompt(userInput: String): String {
        val temperature = currentWeatherData?.main?.temp?.let { "${it}°C" } ?: "未知温度"
        val weatherDesc = currentWeatherData?.weather?.firstOrNull()?.main ?: "未知天气"

        val userInfo = UserPreferences(this).getUserInfo()
        val userGender = userInfo.gender

        // 检查衣物列表是否为空
        if (defaultClothingItems.isEmpty()) {
            return """
        为${userGender}性用户提供建议：
        
        注意：当前衣柜中没有可用的衣物数据。
        
        场景：$userInput
        天气：$temperature, $weatherDesc
        
        请提供通用的穿搭建议。
        """.trimIndent()
        }

        // 按类别组织衣物
        val categorizedClothes = defaultClothingItems.groupBy { it.category }
        val clothingDesc = categorizedClothes.entries.joinToString("\n\n") { (category, items) ->
            val itemsList = items.joinToString("\n") {
                "- ${it.name}（${it.style}风格，${it.thickness}厚度）"
            }
            "${category.uppercase()}类：\n$itemsList"
        }

        return """
    为${userGender}性用户从以下衣柜中选择搭配：

    $clothingDesc
    
    场景：$userInput
    天气：$temperature, $weatherDesc
    
    请简洁回答：
    1. 具体搭配方案（从以上衣物中选择）
    2. 一句话说明原因
    """.trimIndent()
    }

    private fun loadInitialClothing() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedItems = if (prefs.contains(KEY_ITEMS)) {
                val jsonString = prefs.getString(KEY_ITEMS, null)
                try {
                    if (jsonString != null) {
                        Gson().fromJson<List<ClothingItem>>(
                            jsonString,
                            object : TypeToken<List<ClothingItem>>() {}.type
                        ) ?: emptyList()
                    } else {
                        emptyList()
                    }
                } catch (e: Exception) {
                    Log.e("RecommendActivity", "Error parsing saved items", e)
                    emptyList()
                }
            } else {
                val defaultList = loadDefaultClothingFromAssets()
                if (defaultList.isNotEmpty()) {
                    saveUserItemsToLocal(defaultList)
                }
                defaultList
            }

            Log.d("RecommendActivity", "Loaded ${savedItems.size} items")

            // 更新默认衣物列表
            defaultClothingItems = savedItems

        } catch (e: Exception) {
            Log.e("RecommendActivity", "Error in loadInitialClothing", e)
            // 确保即使出错也初始化为空列表
            defaultClothingItems = emptyList()
        }
    }


    private fun requestLocationAndLoadWeather() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
        } else {
            fetchLocationAndLoadWeather()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
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

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

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
            override fun onResponse(
                call: Call<WeatherResponse>,
                response: Response<WeatherResponse>
            ) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful) {
                    currentWeatherData = response.body()
                    displayRecommendations(currentWeatherData)
                } else {
                    btnRetry.visibility = View.VISIBLE
                    Toast.makeText(
                        this@RecommendActivity,
                        "获取天气信息失败: ${response.message()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                btnRetry.visibility = View.VISIBLE
                Toast.makeText(
                    this@RecommendActivity,
                    "获取天气失败：${t.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun fetchWeatherByCity(cityName: String) {
        val apiKey = BuildConfig.WEATHER_API_KEY
        val call = weatherService.getCurrentWeatherByCity(cityName, apiKey)
        call.enqueue(object : Callback<WeatherResponse> {
            override fun onResponse(
                call: Call<WeatherResponse>,
                response: Response<WeatherResponse>
            ) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful) {
                    currentWeatherData = response.body()
                    displayRecommendations(currentWeatherData)
                } else {
                    btnRetry.visibility = View.VISIBLE
                    Toast.makeText(
                        this@RecommendActivity,
                        "获取天气信息失败: ${response.message()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                btnRetry.visibility = View.VISIBLE
                Toast.makeText(
                    this@RecommendActivity,
                    "获取天气失败：${t.message}",
                    Toast.LENGTH_LONG
                ).show()
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

    private fun displayRecommendations(weather: WeatherResponse?) {
        updateWeatherUI(weather)

        if (weather == null) {
            tvRecommendations.text = "无法获取天气，暂时不能提供穿搭建议。"
            return
        }

        tvRecommendations.text = "请输入你的场景或需求，获取智能穿搭建议"
    }

    private fun loadDefaultClothingFromAssets(): List<ClothingItem> {
        return try {
            val jsonString = assets.open(DEFAULT_JSON).bufferedReader(Charsets.UTF_8).use { it.readText() }
            try {
                Gson().fromJson<List<ClothingItem>>(
                    jsonString,
                    object : TypeToken<List<ClothingItem>>() {}.type
                ) ?: emptyList()
            } catch (e: Exception) {
                Log.e("RecommendActivity", "Error parsing default JSON", e)
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("RecommendActivity", "Error loading default clothing from assets", e)
            emptyList()
        }
    }

    private fun saveUserItemsToLocal(items: List<ClothingItem>) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = Gson().toJson(items)
            prefs.edit().putString(KEY_ITEMS, jsonString).apply()
        } catch (e: Exception) {
            Log.e("RecommendActivity", "Error saving user items", e)
        }
    }

    data class ClothingItem(
        val name: String,
        val style: String,
        val thickness: String,
        val category: String,
        val imageUri: String
    )
}