package com.example.smartwardrobe.data

data class WeatherResponse(
    val name: String,            // 城市名称
    val main: Main,
    val weather: List<Weather>
)

data class Main(val temp: Double)
data class Weather(val main: String)
