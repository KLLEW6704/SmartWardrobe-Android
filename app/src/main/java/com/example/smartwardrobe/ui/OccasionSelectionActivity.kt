package com.example.smartwardrobe.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.example.smartwardrobe.R

class OccasionSelectionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_occasion_selection)

        val rgOccasion = findViewById<RadioGroup>(R.id.rgOccasion)
        val btnNext = findViewById<Button>(R.id.btnNext)

        btnNext.setOnClickListener {
            val occasion = findViewById<RadioButton>(
                rgOccasion.checkedRadioButtonId
            ).text.toString()
            val intent = Intent(this, RecommendActivity::class.java)
            intent.putExtra("occasion", occasion)
            startActivity(intent)
        }
    }
}
