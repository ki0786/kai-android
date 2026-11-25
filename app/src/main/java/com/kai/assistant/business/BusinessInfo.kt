package com.kai.assistant.business

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

/** What a customer can ask about. Each one maps to a field of [BusinessInfo]. */
enum class Topic { Address, Hours, Phone, Website, Catalog, Price, Delivery, Offers }

data class Product(val name: String, val priceRange: String)

/** Minutes from midnight. [closeMinute] is always after [openMinute]. */
data class DayHours(val openMinute: Int, val closeMinute: Int)

/** A day that is missing from [days] is closed. */
data class OpeningHours(val days: Map<DayOfWeek, DayHours> = emptyMap()) {
    val isSet: Boolean get() = days.isNotEmpty()

    fun forDay(day: DayOfWeek): DayHours? = days[day]
}

/**
 * The only facts KAI may tell a customer. The owner types these in; nothing here is
 * filled in by a model. A blank field means KAI passes that question to the owner.
 */
data class BusinessInfo(
    val businessName: String = "",
    val address: String = "",
    val hours: OpeningHours = OpeningHours(),
    val phone: String = "",
    val website: String = "",
    val products: List<Product> = emptyList(),
    val deliveryArea: String = "",
    val offers: String = "",
) {
    fun has(topic: Topic): Boolean = when (topic) {
        Topic.Address -> address.isNotBlank()
        Topic.Hours -> hours.isSet
        Topic.Phone -> phone.isNotBlank()
        Topic.Website -> website.isNotBlank()
        Topic.Catalog -> products.any { it.name.isNotBlank() } || website.isNotBlank()
        Topic.Price -> products.any { it.name.isNotBlank() && it.priceRange.isNotBlank() }
        Topic.Delivery -> deliveryArea.isNotBlank()
        Topic.Offers -> offers.isNotBlank()
    }

    /** Auto-reply can only be switched on once the fallback answers exist. */
    val readyForAutoReply: Boolean get() = has(Topic.Address) && has(Topic.Hours)

    /** Every word and number the owner entered, for checking a reply against it. */
    fun factText(): String = buildString {
        appendLine(businessName)
        appendLine(address)
        appendLine(phone)
        appendLine(website)
        products.forEach { appendLine("${it.name} ${it.priceRange}") }
        appendLine(deliveryArea)
        appendLine(offers)
        ReplyLanguage.entries.forEach { appendLine(hoursText(hours, it)) }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("businessName", businessName)
        put("address", address)
        put("phone", phone)
        put("website", website)
        put("deliveryArea", deliveryArea)
        put("offers", offers)
        put(
            "products",
            JSONArray().also { array ->
                products.forEach { array.put(JSONObject().put("name", it.name).put("priceRange", it.priceRange)) }
            },
        )
        put(
            "hours",
            JSONObject().also { json ->
                hours.days.forEach { (day, span) ->
                    json.put(day.name, JSONObject().put("open", span.openMinute).put("close", span.closeMinute))
                }
            },
        )
    }

    companion object {
        fun fromJson(json: JSONObject): BusinessInfo {
            val products = mutableListOf<Product>()
            val array = json.optJSONArray("products") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                products += Product(item.optString("name"), item.optString("priceRange"))
            }
            val days = mutableMapOf<DayOfWeek, DayHours>()
            val hours = json.optJSONObject("hours") ?: JSONObject()
            DayOfWeek.entries.forEach { day ->
                val span = hours.optJSONObject(day.name) ?: return@forEach
                val open = span.optInt("open", -1)
                val close = span.optInt("close", -1)
                if (open in 0 until close && close <= 24 * 60) days[day] = DayHours(open, close)
            }
            return BusinessInfo(
                businessName = json.optString("businessName"),
                address = json.optString("address"),
                hours = OpeningHours(days),
                phone = json.optString("phone"),
                website = json.optString("website"),
                products = products,
                deliveryArea = json.optString("deliveryArea"),
                offers = json.optString("offers"),
            )
        }
    }
}

/** Business info saved on this phone only. */
class BusinessInfoStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _info = MutableStateFlow(load())
    val info: StateFlow<BusinessInfo> = _info.asStateFlow()

    fun current(): BusinessInfo = _info.value

    fun save(info: BusinessInfo) {
        prefs.edit().putString(KEY, info.toJson().toString()).apply()
        _info.value = info
    }

    private fun load(): BusinessInfo {
        val raw = prefs.getString(KEY, null) ?: return BusinessInfo()
        return runCatching { BusinessInfo.fromJson(JSONObject(raw)) }.getOrDefault(BusinessInfo())
    }

    companion object {
        private const val PREFS = "kai_business_info"
        private const val KEY = "info"

        @Volatile private var instance: BusinessInfoStore? = null

        fun get(context: Context): BusinessInfoStore =
            instance ?: synchronized(this) {
                instance ?: BusinessInfoStore(context.applicationContext).also { instance = it }
            }
    }
}
