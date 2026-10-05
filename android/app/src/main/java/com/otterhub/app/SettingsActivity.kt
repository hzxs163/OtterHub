package com.otterhub.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.otterhub.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.inputBaseUrl.setText(Config.savedUrl(this))
        binding.inputPassword.setText(Config.password(this))
        binding.inputApiToken.setText(Config.apiToken(this))
        binding.switchPrivate.isChecked = Config.privateByDefault(this)

        binding.buttonSave.setOnClickListener { saveAndSignIn() }
        binding.buttonTest.setOnClickListener { saveAndSignIn(keepOpen = true) }
    }

    private fun saveAndSignIn(keepOpen: Boolean = false) {
        save()
        binding.testResult.text = getString(R.string.settings_working)
        binding.buttonSave.isEnabled = false
        binding.buttonTest.isEnabled = false
        Thread {
            val api = OtterApi(this)
            val outcome = runCatching { listOf(api.signIn(), api.selfCheck()).joinToString("\n") }
            runOnUiThread {
                binding.buttonSave.isEnabled = true
                binding.buttonTest.isEnabled = true
                outcome.fold(
                    onSuccess = { text ->
                        binding.testResult.text = text
                        if (!keepOpen) {
                            toast(getString(R.string.settings_saved))
                            finish()
                        }
                    },
                    onFailure = { error ->
                        binding.testResult.text = "失败：${error.message}"
                    }
                )
            }
        }.start()
    }

    private fun save() {
        Config.save(
            this,
            binding.inputBaseUrl.text?.toString().orEmpty().ifBlank { Config.DEFAULT_BASE_URL },
            binding.inputPassword.text?.toString().orEmpty(),
            binding.inputApiToken.text?.toString().orEmpty(),
            binding.switchPrivate.isChecked
        )
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
