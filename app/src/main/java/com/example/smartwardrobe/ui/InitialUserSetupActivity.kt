package com.example.smartwardrobe.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.example.smartwardrobe.R
import com.example.smartwardrobe.data.UserInfo
import com.example.smartwardrobe.data.UserPreferences

class InitialUserSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = UserPreferences(this)
        if (prefs.isUserSet()) {
            startActivity(Intent(this, OccasionSelectionActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_initial_user_setup)

        val rgGender = findViewById<RadioGroup>(R.id.rgGender)
        val rgComfort = findViewById<RadioGroup>(R.id.rgComfort)
        val btnSave = findViewById<Button>(R.id.btnSave)

        btnSave.setOnClickListener {
            val gender = findViewById<RadioButton>(
                rgGender.checkedRadioButtonId
            ).text.toString()
            val comfort = findViewById<RadioButton>(
                rgComfort.checkedRadioButtonId
            ).text.toString()
            prefs.saveUserInfo(UserInfo(gender, comfort))
            startActivity(Intent(this, OccasionSelectionActivity::class.java))
            finish()
        }
    }
}
