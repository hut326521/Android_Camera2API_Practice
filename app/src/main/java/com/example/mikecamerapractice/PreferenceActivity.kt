package com.example.mikecamerapractice

import android.R.attr.entries
import android.R.attr.entryValues
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import kotlinx.android.synthetic.main.activity_preference.*
import java.util.*


class PreferenceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference)
        val getFromCameraActivity = intent.getBundleExtra("cameraAvailableSizeBundle")
        val mySettingsFragmentWithData = SettingsFragment()
        mySettingsFragmentWithData.arguments = getFromCameraActivity
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, mySettingsFragmentWithData)
            .commit()


    }

}