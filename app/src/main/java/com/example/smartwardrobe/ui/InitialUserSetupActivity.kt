package com.example.smartwardrobe.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smartwardrobe.R
import com.example.smartwardrobe.data.UserInfo
import com.example.smartwardrobe.data.UserPreferences

class InitialUserSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = UserPreferences(this)
        if (prefs.isUserSet()) {
            startActivity(Intent(this, RecommendActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_initial_user_setup)

        val rgGender = findViewById<RadioGroup>(R.id.rgGender)
        val rgComfort = findViewById<RadioGroup>(R.id.rgComfort)
        val btnSave = findViewById<Button>(R.id.btnSave)

        // 设置默认选择
        rgGender.check(R.id.rbMale)
        rgComfort.check(R.id.rbNormal)

        btnSave.setOnClickListener {
            try {
                // 确保有选择
                if (rgGender.checkedRadioButtonId == -1 || rgComfort.checkedRadioButtonId == -1) {
                    Toast.makeText(this, "请选择性别和体感偏好", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // 安全获取选中的值
                val genderButton = findViewById<RadioButton>(rgGender.checkedRadioButtonId)
                val comfortButton = findViewById<RadioButton>(rgComfort.checkedRadioButtonId)

                if (genderButton == null || comfortButton == null) {
                    Toast.makeText(this, "请重新选择", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val gender = genderButton.text.toString()
                val comfort = comfortButton.text.toString()

                // 保存用户信息
                prefs.saveUserInfo(UserInfo(gender, comfort))

                // 跳转到主界面
                startActivity(Intent(this, RecommendActivity::class.java))
                finish()
            } catch (e: Exception) {
                // 记录错误并显示友好的错误信息
                e.printStackTrace()
                Toast.makeText(this, "设置失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }
}