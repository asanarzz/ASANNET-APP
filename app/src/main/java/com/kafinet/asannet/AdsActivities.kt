package com.kafinet.asannet

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ActivityAdDetailBinding
import com.kafinet.asannet.databinding.ActivityAdsBinding
import com.kafinet.asannet.databinding.ActivityMyAdsBinding
import com.kafinet.asannet.databinding.ActivityPostAdBinding
import com.kafinet.asannet.databinding.ItemMyAdBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun dpOf(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

/* ===================================================================
 *  صفحه‌ی اصلی بخش «تبلیغات»: جستجو، دسته‌بندی، لیست آگهی‌ها
 * =================================================================== */
class AdsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdsBinding
    private lateinit var adapter: AdsAdapter

    private var allAds: List<Ad> = emptyList()
    private var myAdIds: Set<String> = emptySet()
    private var query: String = ""
    private var selectedCategory: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch { SupabaseClient.logVisit(this@AdsActivity, "تبلیغات") }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnMyAds.setOnClickListener {
            startActivity(Intent(this, MyAdsActivity::class.java))
        }
        binding.btnChats.setOnClickListener {
            startActivity(Intent(this, ChatsActivity::class.java))
        }
        binding.btnPost.setOnClickListener {
            startActivity(Intent(this, PostAdActivity::class.java))
        }

        adapter = AdsAdapter(emptyList()) { ad ->
            val intent = Intent(this, AdDetailActivity::class.java)
            intent.putExtra(AdDetailActivity.EXTRA_AD, ad)
            intent.putExtra(AdDetailActivity.EXTRA_MINE, myAdIds.contains(ad.id))
            startActivity(intent)
        }
        binding.recyclerAds.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerAds.adapter = adapter

        buildChips()

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.swipeRefresh.setOnRefreshListener { loadAds() }
        loadAds()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val n = ChatClient.unread(this@AdsActivity)
            binding.btnChats.text = if (n > 0) "پیام‌ها ($n)" else "پیام‌ها"
        }
    }

    private fun buildChips() {
        binding.layoutChips.removeAllViews()
        val names = listOf("همه") + AdCategories.all
        for (name in names) {
            val chip = TextView(this)
            chip.text = name
            chip.textSize = 15f
            chip.setPadding(dpOf(this, 14), dpOf(this, 8), dpOf(this, 14), dpOf(this, 8))
            chip.setBackgroundResource(R.drawable.ads_chip_bg)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dpOf(this, 8)
            chip.layoutParams = lp
            chip.setOnClickListener {
                selectedCategory = if (name == "همه") null else name
                refreshChips()
                applyFilters()
            }
            binding.layoutChips.addView(chip)
        }
        refreshChips()
    }

    private fun refreshChips() {
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        for (i in 0 until binding.layoutChips.childCount) {
            val chip = binding.layoutChips.getChildAt(i) as TextView
            val name = chip.text.toString()
            val active = if (selectedCategory == null) name == "همه" else name == selectedCategory
            chip.isSelected = active
            chip.setTextColor(if (active) Color.WHITE else muted)
        }
    }

    private fun loadAds() {
        lifecycleScope.launch {
            binding.swipeRefresh.isRefreshing = true
            val result = AdsClient.fetchApproved(this@AdsActivity)
            myAdIds = AdsClient.fetchMine(this@AdsActivity).ads.map { it.id }.toSet()
            allAds = result.ads
            applyFilters()
            binding.swipeRefresh.isRefreshing = false
            if (result.error) {
                Toast.makeText(this@AdsActivity, "خطا در دریافت آگهی‌ها", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyFilters() {
        var list = allAds
        val cat = selectedCategory
        if (cat != null) {
            list = list.filter { it.category == cat }
        }
        if (query.isNotBlank()) {
            val q = query.trim()
            list = list.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.description.contains(q, ignoreCase = true) ||
                    it.city.contains(q, ignoreCase = true)
            }
        }
        adapter.update(list)
        binding.txtEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }
}

/* ===================================================================
 *  جزئیات آگهی: عکس‌ها (زوم)، قیمت، توضیحات و راه‌های ارتباط با فروشنده
 * =================================================================== */
class AdDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AD = "extra_ad"
        const val EXTRA_MINE = "extra_mine"
    }

    private lateinit var binding: ActivityAdDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val ad = intent.getSerializableExtra(EXTRA_AD) as? Ad
        if (ad == null) {
            finish()
            return
        }
        val isMine = intent.getBooleanExtra(EXTRA_MINE, false)

        binding.btnBack.setOnClickListener { finish() }

        binding.txtHeadline.text = ad.title
        binding.txtPrice.text = AdsFormat.price(ad.price)
        binding.txtMeta.text = listOf(ad.category, ad.city, AdsFormat.timeAgo(ad.createdAt))
            .filter { it.isNotBlank() }
            .joinToString(" • ")

        if (ad.description.isBlank()) {
            binding.txtDescription.visibility = View.GONE
        } else {
            binding.txtDescription.text = ad.description
        }

        val seller = ad.sellerName.ifBlank { "کاربر آسان نت" }
        binding.txtSeller.text = "فروشنده: $seller"

        if (ad.phone.isBlank()) {
            binding.txtPhone.visibility = View.GONE
        } else {
            binding.txtPhone.text = "شماره تماس: ${ad.phone}"
        }
        if (ad.eitaaId.isBlank()) {
            binding.txtEitaa.visibility = View.GONE
        } else {
            binding.txtEitaa.text = "آیدی ایتا: @${ad.eitaaId}"
        }

        if (ad.images.isEmpty()) {
            binding.scrollImages.visibility = View.GONE
            binding.txtZoomHint.visibility = View.GONE
        } else {
            val widthPx = resources.displayMetrics.widthPixels
            val heightPx = (resources.displayMetrics.heightPixels * 0.5f).toInt()
            binding.scrollImages.layoutParams.height = heightPx
            binding.scrollImages.requestLayout()
            for ((index, url) in ad.images.withIndex()) {
                val iv = ImageView(this)
                iv.layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.MATCH_PARENT)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                Glide.with(this).load(url).into(iv)
                iv.setOnClickListener {
                    val viewer = Intent(this, AdImageViewerActivity::class.java)
                    viewer.putStringArrayListExtra(AdImageViewerActivity.EXTRA_URLS, ArrayList(ad.images))
                    viewer.putExtra(AdImageViewerActivity.EXTRA_INDEX, index)
                    startActivity(viewer)
                }
                binding.layoutImages.addView(iv)
            }
        }

        buildContactButtons(ad, isMine)
    }

    private fun buildContactButtons(ad: Ad, isMine: Boolean) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (ad.allowCall && ad.phone.isNotBlank()) {
            actions.add("تماس تلفنی" to { launchSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + ad.phone))) })
        }
        if (ad.allowSms && ad.phone.isNotBlank()) {
            actions.add("پیامک" to { launchSafely(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + ad.phone))) })
        }
        if (ad.eitaaId.isNotBlank()) {
            actions.add("پیام در ایتا" to { launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://eitaa.com/" + ad.eitaaId))) })
        }
        if (ad.allowChat && !isMine) {
            actions.add("چت در برنامه" to { openChat(ad) })
        }

        if (actions.isEmpty()) {
            binding.txtNoContact.visibility = View.VISIBLE
            return
        }

        var i = 0
        while (i < actions.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val rowLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (i > 0) rowLp.topMargin = dpOf(this, 8)

            val last = minOf(i + 2, actions.size)
            for (j in i until last) {
                val label = actions[j].first
                val action = actions[j].second
                val btn = TextView(this)
                btn.text = label
                btn.textSize = 17f
                btn.gravity = Gravity.CENTER
                btn.setTypeface(null, Typeface.BOLD)
                btn.setPadding(0, dpOf(this, 14), 0, dpOf(this, 14))
                if (j == 0) {
                    btn.setBackgroundResource(R.drawable.ads_btn_bg)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundResource(R.drawable.ads_input_bg)
                    btn.setTextColor(ContextCompat.getColor(this, R.color.primary))
                }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (j % 2 == 1) lp.marginStart = dpOf(this, 8)
                btn.layoutParams = lp
                btn.setOnClickListener { action() }
                row.addView(btn)
            }
            binding.layoutContact.addView(row, rowLp)
            i += 2
        }
    }

    private fun openChat(ad: Ad) {
        Toast.makeText(this, "در حال باز کردن گفتگو…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = ChatClient.start(this@AdDetailActivity, ad.id)
            val chatId = result.chatId
            if (chatId == null) {
                Toast.makeText(this@AdDetailActivity, result.message, Toast.LENGTH_LONG).show()
            } else {
                val intent = Intent(this@AdDetailActivity, ChatActivity::class.java)
                intent.putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
                intent.putExtra(ChatActivity.EXTRA_TITLE, ad.title)
                startActivity(intent)
            }
        }
    }

    private fun launchSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "این کار روی گوشی شما ممکن نیست", Toast.LENGTH_SHORT).show()
        }
    }
}

/* ===================================================================
 *  ثبت آگهی جدید (بعد از ثبت، تا تأیید مدیر نمایش داده نمی‌شود)
 * =================================================================== */
class PostAdActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPostAdBinding
    private val picked = ArrayList<Uri>()
    private var category: String = ""

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        for (u in uris) {
            if (picked.size >= 4) break
            if (!picked.contains(u)) picked.add(u)
        }
        renderThumbs()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostAdBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.txtCategory.setOnClickListener {
            val items = AdCategories.all.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("دسته‌بندی")
                .setItems(items) { _, which ->
                    category = items[which]
                    binding.txtCategory.text = category
                    binding.txtCategory.setTextColor(ContextCompat.getColor(this, R.color.text_main))
                }
                .show()
        }

        binding.btnAddImage.setOnClickListener { pickImages.launch("image/*") }
        binding.btnSubmit.setOnClickListener { submit() }
        renderThumbs()
    }

    private fun renderThumbs() {
        binding.layoutImages.removeAllViews()
        val size = dpOf(this, 90)
        for (uri in picked.toList()) {
            val iv = ImageView(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = dpOf(this, 8)
            iv.layoutParams = lp
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(this).load(uri).centerCrop().into(iv)
            iv.setOnClickListener {
                picked.remove(uri)
                renderThumbs()
            }
            binding.layoutImages.addView(iv)
        }
        binding.txtImagesLabel.text = "عکس‌ها (${picked.size} از ۴) — برای حذف، روی عکس بزنید"
        binding.btnAddImage.visibility = if (picked.size >= 4) View.GONE else View.VISIBLE
        binding.scrollThumbs.visibility = if (picked.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun submit() {
        val title = binding.editTitle.text.toString().trim()
        val description = binding.editDesc.text.toString().trim()
        val city = binding.editCity.text.toString().trim()
        val phone = AdsFormat.toEnglishDigits(binding.editPhone.text.toString()).filter { it.isDigit() }
        val priceDigits = AdsFormat.toEnglishDigits(binding.editPrice.text.toString()).filter { it.isDigit() }

        if (title.length < 3) { toast("عنوان آگهی را بنویسید (حداقل ۳ حرف)"); return }
        if (category.isBlank()) { toast("دسته‌بندی را انتخاب کنید"); return }
        if (description.length < 10) { toast("توضیحات را کامل‌تر بنویسید (حداقل ۱۰ حرف)"); return }
        if (phone.length < 10 || phone.length > 13) { toast("شماره تماس معتبر وارد کنید"); return }
        if (priceDigits.length > 12) { toast("قیمت واردشده درست نیست"); return }

        val allowCall = binding.cbCall.isChecked
        val allowSms = binding.cbSms.isChecked
        val allowChat = binding.cbChat.isChecked
        var eitaa = binding.editEitaa.text.toString().trim().substringAfterLast('/').removePrefix("@").trim()
        eitaa = AdsFormat.toEnglishDigits(eitaa)
        if (eitaa.isNotEmpty() && !Regex("^[A-Za-z0-9_]{3,40}$").matches(eitaa)) {
            toast("آیدی ایتا فقط حروف انگلیسی، عدد و _ دارد (مثلاً my_shop)")
            return
        }
        if (!allowCall && !allowSms && !allowChat && eitaa.isEmpty()) {
            toast("حداقل یک روش ارتباط را انتخاب کنید")
            return
        }
        val price: Long? = if (priceDigits.isEmpty()) null else priceDigits.toLongOrNull()

        binding.btnSubmit.isEnabled = false
        binding.btnSubmit.text = "در حال ارسال…"
        val sellerName = SessionManager.getFullName(this) ?: "کاربر آسان نت"
        val uris = picked.toList()

        lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) {
                uris.mapNotNull { AdsClient.compressImage(applicationContext, it) }
            }
            if (images.size != uris.size) {
                toast("خواندن یکی از عکس‌ها ممکن نشد")
                binding.btnSubmit.isEnabled = true
                binding.btnSubmit.text = "ثبت آگهی"
                return@launch
            }
            val ok = AdsClient.createAd(
                this@PostAdActivity, title, description, price, category, city, phone, sellerName, images,
                allowCall, allowSms, allowChat, eitaa
            )
            if (ok) {
                Toast.makeText(
                    this@PostAdActivity,
                    "آگهی ثبت شد و بعد از تأیید مدیر نمایش داده می‌شود",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } else {
                toast("ثبت آگهی ناموفق بود. اینترنت را بررسی کنید و دوباره تلاش کنید")
                binding.btnSubmit.isEnabled = true
                binding.btnSubmit.text = "ثبت آگهی"
            }
        }
    }
}

/* ===================================================================
 *  آگهی‌های من: وضعیت (در انتظار / منتشرشده / ردشده) و حذف
 * =================================================================== */
class MyAdsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyAdsBinding
    private lateinit var adapter: MyAdsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyAdsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        adapter = MyAdsAdapter(
            emptyList(),
            { ad ->
                val intent = Intent(this, AdDetailActivity::class.java)
                intent.putExtra(AdDetailActivity.EXTRA_AD, ad)
                intent.putExtra(AdDetailActivity.EXTRA_MINE, true)
                startActivity(intent)
            },
            { ad -> confirmDelete(ad) }
        )
        binding.recyclerMyAds.layoutManager = LinearLayoutManager(this)
        binding.recyclerMyAds.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadMine()
    }

    private fun loadMine() {
        lifecycleScope.launch {
            val result = AdsClient.fetchMine(this@MyAdsActivity)
            adapter.update(result.ads)
            binding.txtEmpty.visibility = if (result.ads.isEmpty()) View.VISIBLE else View.GONE
            if (result.error) {
                Toast.makeText(this@MyAdsActivity, "خطا در دریافت آگهی‌ها", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete(ad: Ad) {
        AlertDialog.Builder(this)
            .setTitle("حذف آگهی")
            .setMessage("آگهی «${ad.title}» حذف شود؟")
            .setPositiveButton("حذف") { _, _ ->
                lifecycleScope.launch {
                    val ok = AdsClient.deleteMine(this@MyAdsActivity, ad.id)
                    if (ok) {
                        Toast.makeText(this@MyAdsActivity, "آگهی حذف شد", Toast.LENGTH_SHORT).show()
                        loadMine()
                    } else {
                        Toast.makeText(this@MyAdsActivity, "حذف ناموفق بود", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }
}

class MyAdsAdapter(
    private var items: List<Ad>,
    private val onOpen: (Ad) -> Unit,
    private val onDelete: (Ad) -> Unit
) : RecyclerView.Adapter<MyAdsAdapter.VH>() {

    class VH(val b: ItemMyAdBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemMyAdBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ad = items[position]
        holder.b.txtTitle.text = ad.title
        holder.b.txtPrice.text = AdsFormat.price(ad.price)

        val statusText: String
        val statusColor: String
        when (ad.status) {
            "approved" -> { statusText = "منتشر شده"; statusColor = "#059669" }
            "rejected" -> { statusText = "رد شده"; statusColor = "#DC2626" }
            else -> { statusText = "در انتظار تأیید مدیر"; statusColor = "#D97706" }
        }
        holder.b.txtStatus.text = statusText
        holder.b.txtStatus.setTextColor(Color.parseColor(statusColor))

        if (ad.status == "rejected") {
            val reason = ad.rejectReason
            holder.b.txtReject.visibility = View.VISIBLE
            holder.b.txtReject.text = "دلیل: " + (if (reason.isNullOrBlank()) "ذکر نشده" else reason)
        } else {
            holder.b.txtReject.visibility = View.GONE
        }

        val first = ad.images.firstOrNull()
        if (first != null) {
            Glide.with(holder.b.imgAd.context).load(first).centerCrop().into(holder.b.imgAd)
        } else {
            Glide.with(holder.b.imgAd.context).clear(holder.b.imgAd)
            holder.b.imgAd.setImageDrawable(null)
        }

        holder.b.root.setOnClickListener { onOpen(ad) }
        holder.b.btnDelete.setOnClickListener { onDelete(ad) }
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<Ad>) {
        items = newItems
        notifyDataSetChanged()
    }
}
