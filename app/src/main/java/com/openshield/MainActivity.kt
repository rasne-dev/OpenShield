package com.openshield

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.openshield.data.db.BlockedLogEntity
import com.openshield.data.db.SpamNumberEntity
import com.openshield.data.db.WhitelistEntity
import com.openshield.ui.MainViewModel
import com.openshield.ui.MessageHistoryScreen
import com.openshield.ui.SuspiciousReviewDialog
import com.openshield.data.repository.ConsentManager
import com.openshield.ui.OnboardingScreen
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*

// ─── Renkler ─────────────────────────────────────────────────────────────────

val BgDark     = Color(0xFF080D18)
val Surface1   = Color(0xFF0F1623)
val Surface2   = Color(0xFF161F30)
val Card1      = Color(0xFF1C2840)
val AccentBlue = Color(0xFF3B82F6)
val AccentCyan = Color(0xFF22D3EE)
val Red        = Color(0xFFEF4444)
val Amber      = Color(0xFFF59E0B)
val Green      = Color(0xFF22C55E)
val TextPri    = Color(0xFFF8FAFC)
val TextSec    = Color(0xFF94A3B8)
val TextMuted  = Color(0xFF475569)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle     = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            OpenShieldTheme {
                // Nav bar beyaz kalma düzeltmesi
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        WindowCompat.getInsetsController(window, view).apply {
                            isAppearanceLightStatusBars     = false
                            isAppearanceLightNavigationBars = false
                        }
                    }
                }
                RootScreen()
            }
        }
    }
}

@Composable
fun OpenShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BgDark, surface = Surface1, primary = AccentBlue,
            onBackground = TextPri, onSurface = TextPri,
        ),
        content = content
    )
}

enum class Tab { HOME, BLACKLIST, WHITELIST, LOG, SETTINGS }

// ─── Ana Ekran ────────────────────────────────────────────────────────────────

@Composable
fun RootScreen() {
    val context = LocalContext.current
    val consentManager = remember { ConsentManager(context) }
    var onboardingDone by remember { mutableStateOf(consentManager.onboardingDone) }

    if (!onboardingDone) {
        OnboardingScreen(
            onComplete = { communityConsent ->
                consentManager.communityConsent = communityConsent
                consentManager.onboardingDone   = true
                onboardingDone = true
            }
        )
    } else {
        MainScreen()
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val context            = LocalContext.current
    val dataSharingConsent = remember { mutableStateOf(ConsentManager(context).communityConsent) }

    val spamNumbers      by viewModel.spamNumbers.collectAsState()
    val whitelist        by viewModel.whitelist.collectAsState()
    val blockedLog       by viewModel.blockedLog.collectAsState()
    val smsHistory       by viewModel.smsHistory.collectAsState()
    val smsFeedback      by viewModel.smsFeedback.collectAsState()
    val communitySummary by viewModel.communitySummary.collectAsState()
    val pendingReviews   by viewModel.pendingReviews.collectAsState()
    val lastSyncTime     by viewModel.lastSyncTime.collectAsState()

    var activeTab      by remember { mutableStateOf(Tab.HOME) }
    val isProtectionOn by viewModel.isProtectionOn.collectAsState()
    var hasPermission  by remember { mutableStateOf(false) }
    var dataSharing    by remember { dataSharingConsent }
    var showSuspiciousDialog by remember { mutableStateOf(true) }

    LaunchedEffect(pendingReviews.size) {
        if (pendingReviews.isNotEmpty()) {
            showSuspiciousDialog = true
        }
    }

    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasPermission = perms[Manifest.permission.RECEIVE_SMS] == true
        if (perms[Manifest.permission.READ_SMS] == true) viewModel.refreshSmsHistory()
    }

    LaunchedEffect(activeTab, hasPermission) {
        if (activeTab == Tab.LOG && hasPermission) viewModel.refreshSmsHistory()
    }

    Box(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when (activeTab) {
                    Tab.HOME -> HomeTab(
                        isOn               = isProtectionOn,
                        hasPermission      = hasPermission,
                        spamCount          = spamNumbers.size,
                        blockedCount       = blockedLog.size,
                        pendingCount       = pendingReviews.size,
                        dataSharingEnabled = dataSharing,
                        recentBlocked      = blockedLog.take(3),
                        lastSyncTime       = lastSyncTime,
                        onToggle           = { viewModel.setProtectionOn(it) },
                        onOpenPending      = { showSuspiciousDialog = true },
                        onDismissAllPending = { viewModel.dismissAllPending(markAsSafe = true) },
                        onRequestPermission = {
                            val perms = buildList {
                                add(Manifest.permission.RECEIVE_SMS)
                                add(Manifest.permission.READ_SMS)
                                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permissionLauncher.launch(perms.toTypedArray())
                        }
                    )
                    Tab.BLACKLIST -> BlacklistTab(
                        numbers  = spamNumbers,
                        onAdd    = { num, label -> viewModel.addSpam(num, label) },
                        onRemove = { num -> viewModel.removeSpam(num) }
                    )
                    Tab.WHITELIST -> WhitelistTab(
                        numbers  = whitelist,
                        onAdd    = { num, name -> viewModel.addWhitelist(num, name) },
                        onRemove = { num -> viewModel.removeWhitelist(num) }
                    )
                    Tab.LOG -> MessageHistoryScreen(
                        messages      = smsHistory,
                        feedback      = smsFeedback,
                        summary       = communitySummary,
                        hasPermission = hasPermission,
                        onRefresh     = {
                            viewModel.refreshSmsHistory()
                            viewModel.refreshCommunitySummary()
                        },
                        onClearMarks  = { viewModel.clearFeedback() },
                        onMark        = { msg, verdict -> viewModel.markSms(msg.id, msg.sender, verdict) },
                        onMarkSender  = { msgs, verdict -> viewModel.markSenderMessages(msgs, verdict) }
                    )
                    Tab.SETTINGS -> SettingsTab(
                        dataSharing         = dataSharing,
                        onDataSharingChange = {
                            dataSharing = it
                            ConsentManager(context).communityConsent = it
                        },
                        onClearAll = { viewModel.clearAllData() }
                    )
                }
            }
            if (showSuspiciousDialog && pendingReviews.isNotEmpty()) {
                SuspiciousReviewDialog(
                    pendingReviews  = pendingReviews,
                    onDecide        = { item, isSpam -> viewModel.decideSuspicious(item, isSpam) },
                    onDecideAllSafe = { viewModel.dismissAllPending(markAsSafe = true) },
                    onDismiss       = { showSuspiciousDialog = false }
                )
            }
            BottomNavBar(activeTab = activeTab, onTabChange = { activeTab = it })
        }
    }
}

// ─── Ana Sayfa ────────────────────────────────────────────────────────────────

@Composable
fun HomeTab(
    isOn: Boolean,
    hasPermission: Boolean,
    spamCount: Int,
    blockedCount: Int,
    pendingCount: Int,
    dataSharingEnabled: Boolean,
    recentBlocked: List<BlockedLogEntity>,
    lastSyncTime: Long,
    onToggle: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenPending: () -> Unit = {},
    onDismissAllPending: () -> Unit = {}
) {
    val pulse = rememberInfiniteTransition(label = "p")
    val scale by pulse.animateFloat(
        initialValue = 0.97f, targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(1400, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "s"
    )

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {

        // Header
        item {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Surface1, BgDark)))
                    .padding(top = 60.dp, bottom = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.scale(if (isOn) scale else 1f).size(96.dp).clip(CircleShape)
                            .background(
                                if (isOn) Brush.radialGradient(listOf(AccentBlue.copy(0.25f), Color.Transparent))
                                else Brush.radialGradient(listOf(TextMuted.copy(0.1f), Color.Transparent))
                            )
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(68.dp).clip(CircleShape).background(
                                if (isOn) Brush.linearGradient(listOf(AccentBlue, AccentCyan))
                                else Brush.linearGradient(listOf(Surface2, Card1))
                            )
                        ) { Text("🛡", fontSize = 32.sp) }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("OpenShield", color = TextPri, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("SMS Spam Koruma", color = TextSec, fontSize = 14.sp)
                    Spacer(Modifier.height(20.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Card1)
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (isOn) Green else TextMuted))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isOn) "Koruma Aktif" else "Koruma Pasif",
                            color = if (isOn) Green else TextMuted,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = isOn, onCheckedChange = onToggle,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White, checkedTrackColor = AccentBlue,
                                uncheckedThumbColor = TextMuted, uncheckedTrackColor = Surface2
                            )
                        )
                    }
                }
            }
        }

        // SMS izni uyarısı
        if (!hasPermission) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Amber.copy(0.15f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Amber, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("SMS İzni Gerekli", color = Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Spam SMS'leri engellemek için izin verin", color = TextSec, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onRequestPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = Amber),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text("İzin Ver", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        // Bekleyen şüpheli uyarısı
        if (pendingCount > 0) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .clickable { onOpenPending() },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Amber.copy(0.12f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🟡", fontSize = 22.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "$pendingCount şüpheli mesaj bekliyor",
                                color = TextPri, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            )
                            Text("İncelemek için dokunun", color = TextSec, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = onDismissAllPending,
                            colors = ButtonDefaults.textButtonColors(contentColor = Green)
                        ) {
                            Text("Tümünü Onayla", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // İstatistik kartları
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(Modifier.weight(1f), blockedCount.toString(), "Engellenen", Red)
                StatCard(Modifier.weight(1f), spamCount.toString(), "Kara Liste", Amber)
                StatCard(
                    Modifier.weight(1f),
                    if (dataSharingEnabled) "Açık" else "Kapalı",
                    "Topluluk",
                    if (dataSharingEnabled) AccentBlue else TextMuted
                )
            }
        }

        // Topluluk sync durumu
        if (dataSharingEnabled) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Card1)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape)
                            .background(if (lastSyncTime > 0) Green else TextMuted))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Topluluk Listesi", color = TextPri, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            val syncLabel = if (lastSyncTime > 0) {
                                val mins = (System.currentTimeMillis() - lastSyncTime) / 60_000
                                when {
                                    mins < 60   -> "Son sync: $mins dk önce"
                                    mins < 1440 -> "Son sync: ${mins / 60} saat önce"
                                    else        -> "Son sync: ${mins / 1440} gün önce"
                                }
                            } else "Wi-Fi bağlantısında güncellenecek"
                            Text(syncLabel, color = TextMuted, fontSize = 11.sp)
                        }
                        Text("📡", fontSize = 16.sp)
                    }
                }
            }
        }

        // Son engellenenler
        if (recentBlocked.isNotEmpty()) {
            item {
                Text(
                    "Son Engellenenler",
                    color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            items(recentBlocked) { log -> RecentBlockedCard(log) }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Card1)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("✅", fontSize = 32.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Engellenen mesaj yok", color = TextPri, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Spam tespit edildiğinde burada görünecek", color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ─── Son engellenen kart ──────────────────────────────────────────────────────

@Composable
fun RecentBlockedCard(log: BlockedLogEntity) {
    val scoreColor = when {
        log.score > 0.8f -> Red
        log.score > 0.5f -> Amber
        else             -> Green
    }
    val fmt  = SimpleDateFormat("dd MMM HH:mm", Locale("tr"))
    val date = fmt.format(Date(log.blockedAt))

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Card1)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(scoreColor.copy(0.15f))
                ) {
                    Text("${(log.score * 100).toInt()}", color = scoreColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(log.sender, color = TextPri, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(log.reason, color = TextSec, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(date, color = TextMuted, fontSize = 10.sp)
            }
            if (log.body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = log.body,
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 52.dp)
                )
            }
        }
    }
}

// ─── Kara Liste ───────────────────────────────────────────────────────────────

@Composable
fun BlacklistTab(numbers: List<SpamNumberEntity>, onAdd: (String, String) -> Unit, onRemove: (String) -> Unit) {
    var number by remember { mutableStateOf("") }
    var label  by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = Surface2,
            title = { Text("Numara Ekle", color = TextPri) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = number, onValueChange = { number = it },
                        label = { Text("Telefon Numarası", color = TextSec) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                        colors = outlinedTextFieldColors(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = label, onValueChange = { label = it },
                        label = { Text("Not (isteğe bağlı)", color = TextSec) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                        colors = outlinedTextFieldColors(), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (number.isNotBlank()) { onAdd(number.trim(), label.trim()); number = ""; label = ""; showDialog = false }
                }, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("Ekle") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("İptal", color = TextSec) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ListHeader("Kara Liste", "${numbers.size} numara", "🚫") { showDialog = true }
        if (numbers.isEmpty()) EmptyState("Kara liste boş", "Spam numaraları buraya ekleyin")
        else LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            numbers.forEach { e ->
                item(key = e.number) {
                    NumberCard(e.number, e.label.ifBlank { "Manuel eklendi" }, Red, "🚫") { onRemove(e.number) }
                }
            }
        }
    }
}

// ─── Beyaz Liste ──────────────────────────────────────────────────────────────

@Composable
fun WhitelistTab(numbers: List<WhitelistEntity>, onAdd: (String, String) -> Unit, onRemove: (String) -> Unit) {
    var number by remember { mutableStateOf("") }
    var name   by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = Surface2,
            title = { Text("Güvenli Numara Ekle", color = TextPri) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = number, onValueChange = { number = it },
                        label = { Text("Telefon Numarası", color = TextSec) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                        colors = outlinedTextFieldColors(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("İsim (isteğe bağlı)", color = TextSec) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                        colors = outlinedTextFieldColors(), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (number.isNotBlank()) { onAdd(number.trim(), name.trim()); number = ""; name = ""; showDialog = false }
                }, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Ekle", color = Color.Black) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("İptal", color = TextSec) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ListHeader("Beyaz Liste", "${numbers.size} güvenli numara", "✅") { showDialog = true }
        if (numbers.isEmpty()) EmptyState("Beyaz liste boş", "Güvenilir numaraları buraya ekleyin")
        else LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            numbers.forEach { e ->
                item(key = e.number) {
                    NumberCard(e.number, e.name.ifBlank { "güvenli numara" }, Green, "✅") { onRemove(e.number) }
                }
            }
        }
    }
}

// ─── Ayarlar ──────────────────────────────────────────────────────────────────

@Composable
fun SettingsTab(dataSharing: Boolean, onDataSharingChange: (Boolean) -> Unit, onClearAll: () -> Unit) {
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = Surface2,
            title = { Text("Tüm Verileri Sil", color = TextPri) },
            text = { Text("Kara liste, beyaz liste ve geçmiş tamamen silinecek. Bu işlem geri alınamaz.", color = TextSec) },
            confirmButton = {
                Button(onClick = { onClearAll(); showClearDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("İptal", color = TextSec) } }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(
                modifier = Modifier.fillMaxWidth().background(Surface1)
                    .statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text("\u2699\uFE0F  Ayarlar", color = TextPri, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }

        item {
            SettingsSection("Gizlilik") {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📡", fontSize = 22.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Topluluk Veri Paylaşımı", color = TextPri, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text("Spam numaralar anonim olarak paylaşılır (yalnızca Wi-Fi)", color = TextSec, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = dataSharing, onCheckedChange = onDataSharingChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White, checkedTrackColor = AccentBlue,
                            uncheckedThumbColor = TextMuted, uncheckedTrackColor = Surface2
                        )
                    )
                }
            }
        }

        item {
            SettingsSection("Veri Yönetimi") {
                Row(modifier = Modifier.fillMaxWidth().clickable { showClearDialog = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("🗑️", fontSize = 22.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Tüm Verileri Sil", color = Red, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text("Kara liste, beyaz liste ve geçmişi temizle", color = TextSec, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
                }
            }
        }

        item {
            SettingsSection("Hakkında") {
                SettingsInfoRow("🛡️", "OpenShield", "SMS Spam Engelleme")
                HorizontalDivider(color = Surface2, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsInfoRow("📓", "Versiyon", BuildConfig.VERSION_NAME)
                HorizontalDivider(color = Surface2, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsInfoRow("🔒", "Lisans", "GPL-3.0")
                HorizontalDivider(color = Surface2, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsInfoRow("📡", "İnternet", "Yalnızca topluluk verisi için")
            }
        }
    }
}

// ─── Ortak Bileşenler ─────────────────────────────────────────────────────────

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Card1)) {
            Column { content() }
        }
    }
}

@Composable
fun SettingsInfoRow(icon: String, title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.width(14.dp))
        Text(title, color = TextPri, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextSec, fontSize = 12.sp)
    }
}

@Composable
fun ListHeader(title: String, subtitle: String, icon: String, onAdd: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().background(Surface1)
        .statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPri, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextSec, fontSize = 12.sp)
        }
        FloatingActionButton(onClick = onAdd, containerColor = AccentBlue, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Add, contentDescription = "Ekle", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun NumberCard(number: String, subtitle: String, accentColor: Color, icon: String, onDelete: () -> Unit) {
    var showDelete by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
        .clickable { showDelete = !showDelete },
        shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Card1)) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center,
                modifier = Modifier.size(42.dp).clip(CircleShape).background(accentColor.copy(0.15f))
            ) { Text(icon, fontSize = 16.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(number, color = TextPri, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextSec, fontSize = 12.sp)
            }
            if (showDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Sil", tint = Red)
                }
            }
        }
    }
}

@Composable
fun StatCard(modifier: Modifier, value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(Card1).padding(vertical = 16.dp)) {
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextSec, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun EmptyState(title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📭", fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text(title, color = TextPri, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextSec, fontSize = 13.sp)
        }
    }
}

@Composable
fun BottomNavBar(activeTab: Tab, onTabChange: (Tab) -> Unit) {
    NavigationBar(containerColor = Surface1, tonalElevation = 0.dp) {
        listOf(
            Triple(Tab.HOME,      Icons.Default.Home,                 "Ana Sayfa"),
            Triple(Tab.BLACKLIST, Icons.Default.Block,                "Kara Liste"),
            Triple(Tab.WHITELIST, Icons.Default.CheckCircle,          "Beyaz Liste"),
            Triple(Tab.LOG,       Icons.AutoMirrored.Filled.List,     "Geçmiş"),
            Triple(Tab.SETTINGS,  Icons.Default.Settings,             "Ayarlar"),
        ).forEach { (tab, icon, label) ->
            NavigationBarItem(
                selected = activeTab == tab,
                onClick  = { onTabChange(tab) },
                icon     = { Icon(icon, contentDescription = label) },
                label    = { Text(label, fontSize = 10.sp) },
                colors   = NavigationBarItemDefaults.colors(
                    selectedIconColor   = AccentBlue, selectedTextColor   = AccentBlue,
                    unselectedIconColor = TextMuted,  unselectedTextColor = TextMuted,
                    indicatorColor      = AccentBlue.copy(0.15f)
                )
            )
        }
    }
}

@Composable
fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentBlue, unfocusedBorderColor = TextMuted,
    focusedTextColor = TextPri, unfocusedTextColor = TextPri, cursorColor = AccentBlue
)