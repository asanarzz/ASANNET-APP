package com.kafinet.asannet

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.kafinet.asannet.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val categories = listOf(
            CategoryEntry(ContentType.IMAGE, getString(R.string.cat_image), R.drawable.ic_image, R.drawable.icon_circle_image),
            CategoryEntry(ContentType.VIDEO, getString(R.string.cat_video), R.drawable.ic_video, R.drawable.icon_circle_video),
            CategoryEntry(ContentType.BANNER, getString(R.string.cat_banner), R.drawable.ic_banner, R.drawable.icon_circle_banner),
            CategoryEntry(ContentType.LINK, getString(R.string.cat_link), R.drawable.ic_link, R.drawable.icon_circle_link),
            CategoryEntry(ContentType.FILE, getString(R.string.cat_file), R.drawable.ic_file, R.drawable.icon_circle_file),
            CategoryEntry(ContentType.TEST, getString(R.string.cat_test), R.drawable.ic_test, R.drawable.icon_circle_test),
            CategoryEntry(ContentType.POLL, getString(R.string.cat_poll), R.drawable.ic_poll, R.drawable.icon_circle_poll),
            CategoryEntry(ContentType.SOFTWARE, getString(R.string.cat_software), R.drawable.ic_software, R.drawable.icon_circle_software),
            CategoryEntry(ContentType.MUSIC, getString(R.string.cat_music), R.drawable.ic_music, R.drawable.icon_circle_music),
            CategoryEntry(ContentType.RADIO, getString(R.string.cat_radio), R.drawable.ic_radio, R.drawable.icon_circle_radio),
            CategoryEntry(ContentType.FUN, getString(R.string.cat_fun), R.drawable.ic_fun, R.drawable.icon_circle_fun),
            CategoryEntry(null, getString(R.string.cat_docs), R.drawable.ic_docs, R.drawable.icon_circle_docs, isSubmit = true)
        )

        binding.recyclerCategories.layoutManager = GridLayoutManager(this, 4)
        binding.recyclerCategories.adapter = CategoryGridAdapter(categories) { entry ->
            if (entry.isSubmit) {
                startActivity(Intent(this, SubmitDocumentsActivity::class.java))
            } else {
                val intent = Intent(this, CategoryListActivity::class.java)
                intent.putExtra(CategoryListActivity.EXTRA_TYPE, entry.type?.key)
                intent.putExtra(CategoryListActivity.EXTRA_LABEL, entry.label)
                startActivity(intent)
            }
        }

        binding.txtAppVersion.text = getString(R.string.app_version_label, BuildConfig.VERSION_NAME)

        binding.btnLogout.setOnClickListener {
            SessionManager.logout(this)
            val intent = Intent(this, RegistrationActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}
