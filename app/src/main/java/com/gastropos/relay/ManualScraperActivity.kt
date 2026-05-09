package com.gastropos.relay

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

class ManualScraperActivity : AppCompatActivity() {
    private lateinit var statusValue: TextView
    private lateinit var messageValue: TextView
    private lateinit var uploadUrlValue: TextView
    private lateinit var uploadTimeValue: TextView
    private lateinit var firestoreOrderIdValue: TextView
    private lateinit var uploadStatusValue: TextView
    private lateinit var parsedOrderJsonValue: TextView
    private lateinit var debugCompareValue: TextView
    private var resultReceiverRegistered = false
    private var uploadStatusReceiverRegistered = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val neutralColor = Color.parseColor("#616161")
    private val successColor = Color.parseColor("#1B5E20")
    private val errorColor = Color.parseColor("#B71C1C")
    private val requestTimeoutRunnable = Runnable {
        statusValue.text = getString(R.string.manual_scraper_status_failed)
        statusValue.setTextColor(errorColor)
        messageValue.text = getString(R.string.manual_scraper_no_response)
    }

    private val manualResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_MANUAL_SCRAPE_RESULT) return
            uiHandler.removeCallbacks(requestTimeoutRunnable)
            val success = intent.getBooleanExtra(EXTRA_SUCCESS, false)
            val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
            statusValue.text = if (success) {
                getString(R.string.manual_scraper_status_success)
            } else {
                getString(R.string.manual_scraper_status_failed)
            }
            statusValue.setTextColor(if (success) successColor else errorColor)
            messageValue.text = message.ifBlank { getString(R.string.manual_scraper_waiting_result) }
        }
    }

    private val uploadStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != OrderRelayClient.ACTION_UPLOAD_STATUS_UPDATED) return
            val url = intent.getStringExtra(OrderRelayClient.EXTRA_UPLOAD_URL)
            val timeMs = intent.getLongExtra(OrderRelayClient.EXTRA_UPLOAD_TIME_MS, 0L)
            val status = intent.getStringExtra(OrderRelayClient.EXTRA_UPLOAD_STATUS)
            val firestoreOrderId = intent.getStringExtra(OrderRelayClient.EXTRA_FIRESTORE_ORDER_ID)
            val parsedOrderJson = intent.getStringExtra(OrderRelayClient.EXTRA_PARSED_ORDER_JSON)
            val rawScrapeText = intent.getStringExtra(OrderRelayClient.EXTRA_RAW_SCRAPE_TEXT)
            val hasSuccess = intent.hasExtra(OrderRelayClient.EXTRA_UPLOAD_SUCCESS)
            val success = if (hasSuccess) {
                intent.getBooleanExtra(OrderRelayClient.EXTRA_UPLOAD_SUCCESS, false)
            } else {
                null
            }
            if (intent.hasExtra(OrderRelayClient.EXTRA_UPLOAD_STATUS)) {
                updateUploadStatusViews(
                    url = url,
                    timeMs = timeMs,
                    status = status,
                    success = success,
                    firestoreOrderId = firestoreOrderId
                )
            }
            updateParsedOrderJsonView(parsedOrderJson)
            updateDebugCompareView(parsedOrderJson, rawScrapeText)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual_scraper)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.manual_scraper_page_title)

        statusValue = findViewById(R.id.manualScraperStatusValue)
        messageValue = findViewById(R.id.manualScraperMessageValue)
        uploadUrlValue = findViewById(R.id.manualScraperUploadUrlValue)
        uploadTimeValue = findViewById(R.id.manualScraperUploadTimeValue)
        firestoreOrderIdValue = findViewById(R.id.manualScraperFirestoreIdValue)
        uploadStatusValue = findViewById(R.id.manualScraperUploadStatusValue)
        parsedOrderJsonValue = findViewById(R.id.manualScraperParsedOrderValue)
        debugCompareValue = findViewById(R.id.manualScraperDebugCompareValue)
        statusValue.setTextColor(neutralColor)
        uploadStatusValue.setTextColor(neutralColor)

        findViewById<Button>(R.id.manualScraperTriggerButton).setOnClickListener {
            statusValue.text = getString(R.string.manual_scraper_status_triggered)
            statusValue.setTextColor(neutralColor)
            messageValue.text = getString(R.string.manual_scraper_waiting_result)
            parsedOrderJsonValue.text = getString(R.string.manual_scraper_parsed_order_waiting)
            debugCompareValue.text = getString(R.string.manual_scraper_debug_waiting)
            uiHandler.removeCallbacks(requestTimeoutRunnable)
            uiHandler.postDelayed(requestTimeoutRunnable, REQUEST_TIMEOUT_MS)
            sendBroadcast(Intent(ACTION_MANUAL_SCRAPE).setPackage(packageName))
        }
        findViewById<Button>(R.id.manualScraperOpenGrabButton).setOnClickListener {
            launchMerchantApp(GRAB_PACKAGE)
        }
        findViewById<Button>(R.id.manualScraperOpenShopeeButton).setOnClickListener {
            launchMerchantApp(SHOPEE_PACKAGE)
        }
        findViewById<Button>(R.id.manualScraperCopyDebugButton).setOnClickListener {
            copyDebugText()
        }
        findViewById<Button>(R.id.manualScraperForceUploadButton).setOnClickListener {
            statusValue.text = getString(R.string.manual_scraper_status_triggered)
            statusValue.setTextColor(neutralColor)
            messageValue.text = getString(R.string.manual_scraper_force_upload_waiting)
            uiHandler.removeCallbacks(requestTimeoutRunnable)
            uiHandler.postDelayed(requestTimeoutRunnable, REQUEST_TIMEOUT_MS)
            val result = OrderRelayClient.forceUploadLastCaptured(this)
            messageValue.text = result.message
            if (!result.success) {
                statusValue.text = getString(R.string.manual_scraper_status_failed)
                statusValue.setTextColor(errorColor)
                uiHandler.removeCallbacks(requestTimeoutRunnable)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerManualResultReceiver()
        registerUploadStatusReceiver()
        loadLastUploadStatus()
    }

    override fun onStop() {
        uiHandler.removeCallbacks(requestTimeoutRunnable)
        unregisterManualResultReceiver()
        unregisterUploadStatusReceiver()
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun registerManualResultReceiver() {
        if (resultReceiverRegistered) return
        val filter = IntentFilter(ACTION_MANUAL_SCRAPE_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(manualResultReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(manualResultReceiver, filter)
        }
        resultReceiverRegistered = true
    }

    private fun unregisterManualResultReceiver() {
        if (!resultReceiverRegistered) return
        unregisterReceiver(manualResultReceiver)
        resultReceiverRegistered = false
    }

    private fun registerUploadStatusReceiver() {
        if (uploadStatusReceiverRegistered) return
        val filter = IntentFilter(OrderRelayClient.ACTION_UPLOAD_STATUS_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uploadStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uploadStatusReceiver, filter)
        }
        uploadStatusReceiverRegistered = true
    }

    private fun unregisterUploadStatusReceiver() {
        if (!uploadStatusReceiverRegistered) return
        unregisterReceiver(uploadStatusReceiver)
        uploadStatusReceiverRegistered = false
    }

    private fun loadLastUploadStatus() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val url = prefs.getString(KEY_LAST_UPLOAD_URL, null) ?: OrderRelayClient.getRelayUrl(this)
        val timeMs = prefs.getLong(KEY_LAST_UPLOAD_TIME_MS, 0L)
        val status = prefs.getString(KEY_LAST_UPLOAD_STATUS, null)
        val firestoreOrderId = prefs.getString(KEY_LAST_FIRESTORE_ORDER_ID, null)
        val parsedOrderJson = OrderRelayClient.getLastParsedOrderJson(this)
        val rawScrapeText = OrderRelayClient.getLastRawScrapeText(this)
        val success = if (prefs.contains(KEY_LAST_UPLOAD_SUCCESS)) {
            prefs.getBoolean(KEY_LAST_UPLOAD_SUCCESS, false)
        } else {
            null
        }
        updateUploadStatusViews(
            url = url,
            timeMs = timeMs,
            status = status,
            success = success,
            firestoreOrderId = firestoreOrderId
        )
        updateParsedOrderJsonView(parsedOrderJson)
        updateDebugCompareView(parsedOrderJson, rawScrapeText)
    }

    private fun updateUploadStatusViews(
        url: String?,
        timeMs: Long,
        status: String?,
        success: Boolean?,
        firestoreOrderId: String?
    ) {
        uploadUrlValue.text = url?.takeIf { it.isNotBlank() }
            ?: getString(R.string.manual_scraper_upload_not_available)
        uploadTimeValue.text = if (timeMs > 0L) {
            getString(
                R.string.manual_scraper_upload_time_format,
                DateFormat.getDateTimeInstance().format(Date(timeMs))
            )
        } else {
            getString(R.string.manual_scraper_upload_not_available)
        }
        firestoreOrderIdValue.text = firestoreOrderId?.takeIf { it.isNotBlank() }
            ?: getString(R.string.manual_scraper_upload_not_available)
        uploadStatusValue.text = status?.takeIf { it.isNotBlank() }
            ?: getString(R.string.manual_scraper_upload_not_available)
        uploadStatusValue.setTextColor(
            when (success) {
                true -> successColor
                false -> errorColor
                null -> neutralColor
            }
        )
    }

    private fun updateParsedOrderJsonView(parsedOrderJson: String?) {
        if (parsedOrderJson == null) return
        parsedOrderJsonValue.text = parsedOrderJson.takeIf { it.isNotBlank() }
            ?: getString(R.string.manual_scraper_upload_not_available)
    }

    private fun updateDebugCompareView(parsedOrderJson: String?, rawScrapeText: String?) {
        val rawSection = rawScrapeText
            ?.takeIf { it.isNotBlank() }
            ?.let { text ->
                text.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            }
            ?: getString(R.string.manual_scraper_upload_not_available)
        val parsedSection = summarizeParsedItems(parsedOrderJson)
        debugCompareValue.text = buildString {
            append("RAW SCREEN TEXT\n")
            append(rawSection)
            append("\n\nPARSED ITEMS SUMMARY\n")
            append(parsedSection)
        }
    }

    private fun summarizeParsedItems(parsedOrderJson: String?): String {
        if (parsedOrderJson.isNullOrBlank()) return getString(R.string.manual_scraper_upload_not_available)
        return try {
            val root = JSONObject(parsedOrderJson)
            val orderId = root.optString("order_id", "Unknown")
            val grandTotal = if (root.has("grand_total") && !root.isNull("grand_total")) {
                root.optDouble("grand_total")
            } else {
                null
            }
            val items = root.optJSONArray("items")
            val itemLines = mutableListOf<String>()
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val quantity = item.optInt("quantity", 1)
                    val name = item.optString("name", "Unknown Item")
                    val price = if (item.has("price") && !item.isNull("price")) {
                        item.optDouble("price")
                    } else {
                        null
                    }
                    val note = item.optString("note", "").trim()
                    itemLines.add(
                        if (price != null) {
                            "$quantity x $name (RM${"%.2f".format(price)})"
                        } else {
                            "$quantity x $name"
                        }
                    )
                    if (note.isNotBlank()) {
                        itemLines.add("  note: $note")
                    }
                }
            }
            buildString {
                append("order_id: $orderId\n")
                append("grand_total: ")
                append(grandTotal?.let { "RM${"%.2f".format(it)}" } ?: "N/A")
                append("\nitems:\n")
                append(
                    if (itemLines.isEmpty()) {
                        "- none"
                    } else {
                        itemLines.joinToString("\n") { "- $it" }
                    }
                )
            }
        } catch (_: Exception) {
            getString(R.string.manual_scraper_debug_parse_error)
        }
    }

    private fun copyDebugText() {
        val textToCopy = debugCompareValue.text?.toString().orEmpty()
        if (textToCopy.isBlank()) {
            Toast.makeText(this, getString(R.string.manual_scraper_copy_debug_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.manual_scraper_copy_debug_label),
                textToCopy
            )
        )
        Toast.makeText(this, getString(R.string.manual_scraper_copy_debug_success), Toast.LENGTH_SHORT).show()
    }

    private fun launchMerchantApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val resolved = launchIntent.resolveActivity(packageManager)
        if (resolved == null) {
            Toast.makeText(this, getString(R.string.test_launch_failed, packageName), Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(launchIntent)
        Toast.makeText(this, getString(R.string.test_launch_success, packageName), Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_MANUAL_SCRAPE = "com.gastropos.relay.MANUAL_SCRAPE"
        const val ACTION_MANUAL_SCRAPE_RESULT = "com.gastropos.relay.MANUAL_SCRAPE_RESULT"
        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_FORCE_UPLOAD = "extra_force_upload"
        private const val REQUEST_TIMEOUT_MS = 3000L
        private const val PREFS = "relay_prefs"
        private const val KEY_LAST_UPLOAD_URL = "last_upload_url"
        private const val KEY_LAST_UPLOAD_TIME_MS = "last_upload_time_ms"
        private const val KEY_LAST_UPLOAD_STATUS = "last_upload_status"
        private const val KEY_LAST_UPLOAD_SUCCESS = "last_upload_success"
        private const val KEY_LAST_FIRESTORE_ORDER_ID = "last_firestore_order_id"
        private const val GRAB_PACKAGE = "com.grab.merchant"
        private const val SHOPEE_PACKAGE = "com.shopeepay.merchant.my"
    }
}
