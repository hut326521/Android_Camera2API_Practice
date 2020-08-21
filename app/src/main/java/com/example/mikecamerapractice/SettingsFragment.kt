package com.example.mikecamerapractice

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences,rootKey)

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val getHeightFromCamActivity = arguments?.getSerializable("cameraAvailableSizeBundleHeight") as ArrayList<*>
        val getWidthFromCamActivity = arguments?.getSerializable("cameraAvailableSizeBundleWidth") as ArrayList<*>
        val getIDFromCamActivity = arguments?.getSerializable("cameraAvailableIdBundle") as ArrayList<*>
        val newSizeArray = arrayOfNulls<CharSequence>(getHeightFromCamActivity.size)
        val newIDArray = arrayOfNulls<CharSequence>(getIDFromCamActivity.size)
        for (i in 0 until getHeightFromCamActivity.size)
        {
            newSizeArray[i] = getHeightFromCamActivity[i].toString() + "x" + getWidthFromCamActivity[i].toString()
        }
        for (j in 0 until getIDFromCamActivity.size)
        {
            newIDArray[j] = getIDFromCamActivity[j].toString()
        }

        findPreference<ListPreference>("cameraSize")?.entries = newSizeArray
        findPreference<ListPreference>("cameraSize")?.entryValues = newSizeArray
        findPreference<ListPreference>("cameraID")?.entries = newIDArray
        findPreference<ListPreference>("cameraID")?.entryValues = newIDArray

    }

}