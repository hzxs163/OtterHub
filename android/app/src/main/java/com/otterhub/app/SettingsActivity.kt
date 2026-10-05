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

        binding.buttonSave.setOnClickListener {
            save()
            toast(getString(R.string.settings_saved))
            finish()
        }

        binding.buttonTest.setOnClickListener {
            save()
            binding.testResult.text = "正在测试…"
            Thread {
                val result = runCatching { OtterApi(this).selfCheck() }
                runOnUiThread {
                    binding.testResult.text = result.fold(
                        onSuccess = { it },
                        onFailure = { "失败：${it.message}" }
                    )
                }
            }.start()
        }
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
