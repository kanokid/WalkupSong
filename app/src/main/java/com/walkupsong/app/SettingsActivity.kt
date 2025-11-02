package com.walkupsong.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : androidx.preference.PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            val musicSourcePreference: ListPreference? = findPreference("music_source")
            val spotifyClientIdPreference: EditTextPreference? = findPreference("spotify_client_id")

            spotifyClientIdPreference?.isVisible = musicSourcePreference?.value == "spotify"

            musicSourcePreference?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    spotifyClientIdPreference?.isVisible = newValue == "spotify"
                    true
                }

            val clearDataPreference: Preference? = findPreference("clear_data")
            clearDataPreference?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Clear Batting Order")
                    .setMessage("Are you sure you want to delete all players? This action cannot be undone.")
                    .setPositiveButton("Clear") { _, _ ->
                        val sharedPreferences = requireActivity().getSharedPreferences("walkup-song", Context.MODE_PRIVATE)
                        sharedPreferences.edit().clear().apply()
                        val intent = Intent(requireActivity(), MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
    }
}
