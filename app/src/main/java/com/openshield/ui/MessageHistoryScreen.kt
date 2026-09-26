package com.openshield.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openshield.*
import com.openshield.data.model.SmsHistoryItem
import com.openshield.data.util.PhoneNumberNormalizer
import java.text.SimpleDateFormat
import java.util.*

/**
 * SMS Geçmişi ekranı.
 *
 * Her mesaj kartında:
 *   - "Spam Bildir" → kırmızı, tıklanınca community raporu + kara listeye ekle
 *   - "Güvenilir"   → yeşil, tıklanınca beyaz listeye ekle seçeneği
 *
 * Gönderici başlığında (grup açıkken):
 *   - "Tümünü Spam"     → bu gönderenin tüm mesajlarını spam say
 *   - "Tümünü Güvenilir" → bu gönderenin tüm mesajlarını temiz say
 */
@Composable
fun MessageHistoryScreen(
    messages: List<SmsHistoryItem>,
    feedback: Map<Long, Boolean>,       // id → true=spam, false=güvenilir, null=işaretlenmemiş
    summary: String,
    hasPermission: Boolean,
    onRefresh: () -> Unit,
    onClearMarks: () -> Unit,
    onMark: (SmsHistoryItem, Boolean) -> Unit,                  // tek mesaj işaretle
    onMarkSender: (List<SmsHistoryItem>, Boolean) -> Unit       // gönderici bazlı toplu işaret
) {
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = Surface2,
            title = { Text("İşaretleri Temizle", color = TextPri) },
            text = { Text("Tüm spam / güvenilir işaretleri silinecek.", color = TextSec) },
            confirmButton = {
                Button(
                    onClick = { onClearMarks(); showClearDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Red)
                ) { Text("Temizle") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("İptal", color = TextSec) }
            }
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableIntStateOf(0) } // 0: Tümü, 1: Spam, 2: Güvenilir, 3: İşaretsiz

    val filteredMessages = remember(messages, feedback, searchQuery, selectedFilter) {
        messages.filter { msg ->
            val matchesQuery = searchQuery.isBlank() ||
                msg.sender.contains(searchQuery, ignoreCase = true) ||
                msg.body.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (selectedFilter) {
                1 -> feedback[msg.id] == true
                2 -> feedback[msg.id] == false
                3 -> !feedback.containsKey(msg.id)
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Header ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface1)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("SMS Geçmişi", color = TextPri, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val countLabel = if (filteredMessages.size != messages.size) {
                    "${filteredMessages.size} / ${messages.size} mesaj"
                } else {
                    "${messages.size} mesaj"
                }
                val subtitle = if (summary.isNotBlank()) "$countLabel · $summary" else countLabel
                Text(subtitle, color = TextSec, fontSize = 12.sp)
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Yenile", tint = TextSec)
            }
            if (feedback.isNotEmpty()) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "İşaretleri temizle", tint = TextSec)
                }
            }
        }

        // ── Arama ve Filtreleme ──────────────────────────────────────────────
        if (hasPermission && messages.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Numara veya metin ara...", color = TextMuted, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Temizle", tint = TextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Surface2,
                        focusedContainerColor = Card1,
                        unfocusedContainerColor = Card1,
                        focusedTextColor = TextPri,
                        unfocusedTextColor = TextPri
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Tümü", "Spam", "Güvenilir", "İşaretsiz").forEachIndexed { idx, label ->
                        val isSelected = selectedFilter == idx
                        Surface(
                            onClick = { selectedFilter = idx },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) AccentBlue else Card1,
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else TextSec,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── İçerik ──────────────────────────────────────────────────────────
        when {
            !hasPermission -> EmptyState("SMS izni gerekli", "Geçmiş için READ_SMS izni verin")
            messages.isEmpty() -> EmptyState("Geçmiş boş", "Gelen SMS kayıtları burada görünür")
            filteredMessages.isEmpty() -> EmptyState("Sonuç bulunamadı", "Arama kriterlerine uyan SMS yok")
            else -> {
                // Gönderici bazlı grupla
                val grouped = remember(filteredMessages) {
                    filteredMessages.groupBy { it.sender.ifBlank { "Bilinmeyen" } }
                }

                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    grouped.forEach { (sender, senderMessages) ->
                        item(key = "sender_$sender") {
                            SenderGroup(
                                sender = sender,
                                messages = senderMessages,
                                feedback = feedback,
                                onMark = onMark,
                                onMarkAllSpam = { onMarkSender(senderMessages, true) },
                                onMarkAllSafe = { onMarkSender(senderMessages, false) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Gönderici Grubu ─────────────────────────────────────────────────────────

@Composable
fun SenderGroup(
    sender: String,
    messages: List<SmsHistoryItem>,
    feedback: Map<Long, Boolean>,
    onMark: (SmsHistoryItem, Boolean) -> Unit,
    onMarkAllSpam: () -> Unit,
    onMarkAllSafe: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    // Bu gönderenin işaret durumunu hesapla
    val markedCount = messages.count { feedback.containsKey(it.id) }
    val spamCount = messages.count { feedback[it.id] == true }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Card1)
    ) {
        Column {
            // ── Gönderici başlık satırı ──────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                spamCount > 0 -> Red.copy(alpha = 0.18f)
                                markedCount > 0 -> Green.copy(alpha = 0.18f)
                                else -> AccentBlue.copy(alpha = 0.12f)
                            }
                        )
                ) {
                    Text(
                        sender.take(1).uppercase(),
                        color = when {
                            spamCount > 0 -> Red
                            markedCount > 0 -> Green
                            else -> AccentBlue
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        PhoneNumberNormalizer.formatForDisplay(sender),
                        color = TextPri,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${messages.size} mesaj" + if (markedCount > 0) " · $markedCount işaretli" else "",
                        color = TextSec,
                        fontSize = 11.sp
                    )
                }

                // Ok ikonu
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            // ── Genişletilmiş: toplu butonlar + mesaj listesi ────────────
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(
                        color = Surface2,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )

                    // Toplu işlem butonları
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Tümünü Spam
                        FilledTonalButton(
                            onClick = onMarkAllSpam,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Red.copy(alpha = 0.15f),
                                contentColor = Red
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Tümünü Spam", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }

                        // Tümünü Güvenilir
                        FilledTonalButton(
                            onClick = onMarkAllSafe,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Green.copy(alpha = 0.15f),
                                contentColor = Green
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Tümünü Güvenilir", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // Mesaj listesi
                    messages.forEach { msg ->
                        SmsMessageCard(
                            message = msg,
                            markedSpam = feedback[msg.id],
                            onMarkSpam = { onMark(msg, true) },
                            onMarkSafe = { onMark(msg, false) }
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// ─── Tek Mesaj Kartı ─────────────────────────────────────────────────────────

@Composable
fun SmsMessageCard(
    message: SmsHistoryItem,
    markedSpam: Boolean?,       // true=spam, false=güvenilir, null=işaretlenmemiş
    onMarkSpam: () -> Unit,
    onMarkSafe: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale("tr")) }

    val bgColor = when (markedSpam) {
        true  -> Red.copy(alpha = 0.06f)
        false -> Green.copy(alpha = 0.06f)
        null  -> Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Mesaj içeriği
        Text(
            message.body.ifBlank { "(Boş SMS)" },
            color = TextSec,
            fontSize = 12.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    fmt.format(Date(message.receivedAt)),
                    color = TextMuted,
                    fontSize = 10.sp
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.body))
                        copied = true
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = Surface2.copy(alpha = 0.7f),
                    modifier = Modifier.height(22.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Metni Kopyala",
                            tint = if (copied) Green else TextMuted,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            if (copied) "Kopyalandı" else "Kopyala",
                            color = if (copied) Green else TextMuted,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            // İşaret butonları — sıkı padding, compakt
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Spam Bildir
                val isSpam = markedSpam == true
                Surface(
                    onClick = onMarkSpam,
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSpam) Red.copy(alpha = 0.2f) else Surface2,
                    modifier = Modifier.height(28.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = "Spam",
                            tint = if (isSpam) Red else TextMuted,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Spam",
                            color = if (isSpam) Red else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = if (isSpam) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }

                // Güvenilir
                val isSafe = markedSpam == false
                Surface(
                    onClick = onMarkSafe,
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSafe) Green.copy(alpha = 0.2f) else Surface2,
                    modifier = Modifier.height(28.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Güvenilir",
                            tint = if (isSafe) Green else TextMuted,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Güvenilir",
                            color = if (isSafe) Green else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = if (isSafe) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // İşaret göstergesi
        if (markedSpam != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (markedSpam) "✓ Spam olarak işaretlendi" else "✓ Güvenilir olarak işaretlendi",
                color = if (markedSpam) Red.copy(alpha = 0.7f) else Green.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
        }

        HorizontalDivider(
            color = Surface2.copy(alpha = 0.5f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
