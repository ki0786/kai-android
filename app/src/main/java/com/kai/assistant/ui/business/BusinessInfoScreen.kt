package com.kai.assistant.ui.business

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kai.assistant.business.BusinessInfo
import com.kai.assistant.business.BusinessInfoStore
import com.kai.assistant.business.DayHours
import com.kai.assistant.business.OpeningHours
import com.kai.assistant.business.Product
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessInfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { BusinessInfoStore.get(context) }
    val saved by store.info.collectAsStateWithLifecycle()

    var businessName by remember(saved) { mutableStateOf(saved.businessName) }
    var address by remember(saved) { mutableStateOf(saved.address) }
    var phone by remember(saved) { mutableStateOf(saved.phone) }
    var website by remember(saved) { mutableStateOf(saved.website) }
    var deliveryArea by remember(saved) { mutableStateOf(saved.deliveryArea) }
    var offers by remember(saved) { mutableStateOf(saved.offers) }
    var weekdayOpen by remember(saved) {
        mutableStateOf(formatMinute(saved.hours.forDay(DayOfWeek.MONDAY)?.openMinute ?: 10 * 60))
    }
    var weekdayClose by remember(saved) {
        mutableStateOf(formatMinute(saved.hours.forDay(DayOfWeek.MONDAY)?.closeMinute ?: 20 * 60))
    }
    var sundayOpen by remember(saved) {
        mutableStateOf(formatMinute(saved.hours.forDay(DayOfWeek.SUNDAY)?.openMinute))
    }
    var sundayClose by remember(saved) {
        mutableStateOf(formatMinute(saved.hours.forDay(DayOfWeek.SUNDAY)?.closeMinute))
    }
    val products = remember(saved) {
        mutableStateListOf<Product>().also { list ->
            if (saved.products.isEmpty()) list += Product("", "")
            else list.addAll(saved.products)
        }
    }
    var message by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Business info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "KAI answers customers only from what you type here. It never invents a price, stock, discount or delivery promise.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = businessName,
                onValueChange = { businessName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Business name") },
                singleLine = true,
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Showroom address") },
                minLines = 2,
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Phone") },
                singleLine = true,
            )
            OutlinedTextField(
                value = website,
                onValueChange = { website = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Website") },
                singleLine = true,
            )
            Text("Hours (Mon–Sat)", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = weekdayOpen,
                    onValueChange = { weekdayOpen = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Opens (HH:MM)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = weekdayClose,
                    onValueChange = { weekdayClose = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Closes (HH:MM)") },
                    singleLine = true,
                )
            }
            Text("Sunday (leave blank if closed)", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = sundayOpen,
                    onValueChange = { sundayOpen = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Opens") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = sundayClose,
                    onValueChange = { sundayClose = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Closes") },
                    singleLine = true,
                )
            }
            OutlinedTextField(
                value = deliveryArea,
                onValueChange = { deliveryArea = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Delivery area") },
            )
            OutlinedTextField(
                value = offers,
                onValueChange = { offers = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Current offers") },
            )
            Text("Products and price ranges", style = MaterialTheme.typography.titleMedium)
            products.forEachIndexed { index, product ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = product.name,
                        onValueChange = { products[index] = product.copy(name = it) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Product") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = product.priceRange,
                        onValueChange = { products[index] = product.copy(priceRange = it) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Price range") },
                        singleLine = true,
                    )
                }
            }
            TextButton(onClick = { products += Product("", "") }) { Text("Add product") }
            if (message.isNotEmpty()) {
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
            Button(
                onClick = {
                    val week = parseMinute(weekdayOpen) to parseMinute(weekdayClose)
                    if (week.first == null || week.second == null || week.first!! >= week.second!!) {
                        message = "Set Mon–Sat opening hours as HH:MM, with close after open."
                        return@Button
                    }
                    if (address.isBlank()) {
                        message = "Showroom address is required before auto-reply can turn on."
                        return@Button
                    }
                    val days = mutableMapOf<DayOfWeek, DayHours>()
                    val span = DayHours(week.first!!, week.second!!)
                    listOf(
                        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
                    ).forEach { days[it] = span }
                    val sun = parseMinute(sundayOpen) to parseMinute(sundayClose)
                    if (sun.first != null && sun.second != null && sun.first!! < sun.second!!) {
                        days[DayOfWeek.SUNDAY] = DayHours(sun.first!!, sun.second!!)
                    }
                    store.save(
                        BusinessInfo(
                            businessName = businessName.trim(),
                            address = address.trim(),
                            hours = OpeningHours(days),
                            phone = phone.trim(),
                            website = website.trim(),
                            products = products.map { Product(it.name.trim(), it.priceRange.trim()) }
                                .filter { it.name.isNotEmpty() },
                            deliveryArea = deliveryArea.trim(),
                            offers = offers.trim(),
                        ),
                    )
                    message = "Saved on this phone."
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun formatMinute(minute: Int?): String {
    if (minute == null) return ""
    return "%02d:%02d".format(minute / 60, minute % 60)
}

private fun parseMinute(raw: String): Int? {
    val match = Regex("""^(\d{1,2}):(\d{2})$""").find(raw.trim()) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
