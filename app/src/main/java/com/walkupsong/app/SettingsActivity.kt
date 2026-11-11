package com.walkupsong.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : androidx.preference.PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "walkup-song"
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<EditTextPreference>("groq_api_key")?.apply {
                setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    editText.setSelection(editText.text?.length ?: 0)
                }
                setOnPreferenceChangeListener { pref, newValue ->
                    (pref as EditTextPreference).text = (newValue as? String)?.trim().orEmpty()
                    false
                }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    val effective = pref.text?.trim()?.takeIf { it.isNotEmpty() }
                        ?: BuildConfig.GROQ_API_KEY.trim().takeIf { it.isNotEmpty() }
                    if (effective == null) {
                        getString(R.string.groq_key_unset)
                    } else {
                        getString(R.string.groq_key_configured, "••••${effective.takeLast(4)}")
                    }
                }
            }

            val clearDataPreference: Preference? = findPreference("clear_data")
            clearDataPreference?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.clear_batting_order)
                    .setMessage(R.string.clear_batting_order_confirm)
                    .setPositiveButton(R.string.clear_action) { _, _ ->
                        val sharedPreferences = requireActivity().getSharedPreferences("walkup-song", Context.MODE_PRIVATE)
                        sharedPreferences.edit()
                            .remove("players")
                            .remove("selected_player")
                            .apply()
                        val intent = Intent(requireActivity(), MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            }
        }
    }
}
