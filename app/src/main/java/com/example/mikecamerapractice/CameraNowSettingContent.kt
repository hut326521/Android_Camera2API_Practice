package com.example.mikecamerapractice

import android.content.Context
import androidx.preference.PreferenceManager

class CameraNowSettingContent {
    var width: Int = 0
    var height: Int = 0
    var nowCameraID = ""

    init
    {

    }
    fun getNowSettingGo(context:Context)
    {
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString("cameraSize", "3000x4000")
            ?.let { nowCameraPrevSize ->
                val sizeList = nowCameraPrevSize.split('x')
                this.width = sizeList[0].toInt()
                this.height = sizeList[1].toInt()
            }
        this.nowCameraID =
            PreferenceManager.getDefaultSharedPreferences(context).getString("cameraID", "0").toString()
    }




}