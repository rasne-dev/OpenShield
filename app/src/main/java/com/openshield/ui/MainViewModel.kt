package com.openshield.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openshield.data.db.BlockedLogEntity
import com.openshield.data.db.PendingReviewEntity
import com.openshield.data.db.SpamNumberEntity
import com.openshield.data.db.WhitelistEntity
import com.openshield.data.model.SmsHistoryItem
import com.openshield.data.repository.SpamNumberRepository
import com.openshield.detection.rules.SpamTokenExtractor
import com.openshield.data.repository.CommunityRepository
import com.openshield.data.repository.ConsentManager
import com.openshield.worker.WifiSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: SpamNumberRepository,
    private val consentManager: ConsentManager,
    private val communityRepository: CommunityRepository,
    private val wifiSyncManager: WifiSyncManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val spamNumbers: StateFlow<List<SpamNumberEntity>> = repository.spamNumbers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val whitelist: StateFlow<List<WhitelistEntity>> = repository.whitelist
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blockedLog: StateFlow<List<BlockedLogEntity>> = repository.blockedLog
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingReviews: StateFlow<List<PendingReviewEntity>> = repository.pendingReviews
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _smsHistory = MutableStateFlow<List<SmsHistoryItem>>(emptyList())
    val smsHistory: StateFlow<List<SmsHistoryItem>> = _smsHistory

    private val _smsFeedback = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val smsFeedback: StateFlow<Map<Long, Boolean>> = _smsFeedback

    private val _communitySummary = MutableStateFlow("")
    val communitySummary: StateFlow<String> = _communitySummary

    // Ana sayfada sync zamanını göstermek için
    private val _lastSyncTime = MutableStateFlow(consentManager.lastSyncTime)
    val lastSyncTime: StateFlow<Long> = _lastSyncTime

    private val _isProtectionOn = MutableStateFlow(consentManager.isProtectionOn)
    val isProtectionOn: StateFlow<Boolean> = _isProtectionOn

    fun setProtectionOn(enabled: Boolean) {
        consentManager.isProtectionOn = enabled
        _isProtectionOn.value = enabled
    }

    // ─── Kara / Beyaz Liste ───────────────────────────────────────────────────

    fun addSpam(number: String, label: String = "") = viewModelScope.launch {
        repository.addSpam(number, label)
    }

    fun removeSpam(number: String) = viewModelScope.launch {
        repository.removeSpam(number)
    }

    fun addWhitelist(number: String, name: String = "") = viewModelScope.launch {
        repository.addWhitelist(number, name)
    }

    fun removeWhitelist(number: String) = viewModelScope.launch {
        repository.removeWhitelist(number)
    }

    fun clearHistory() = viewModelScope.launch {
        repository.clearHistory()
    }

    fun clearAllData() = viewModelScope.launch {
        repository.clearAllData()
    }

    // ─── Şüpheli Karar ───────────────────────────────────────────────────────

    fun decideSuspicious(item: PendingReviewEntity, isSpam: Boolean) = viewModelScope.launch {
        repository.decideSuspicious(item, isSpam)
        if (consentManager.communityConsent) {
            if (isSpam) {
                val rules = SpamTokenExtractor.sanitizeRules(item.reason.split(","))
                communityRepository.reportSpam(item.sender, rules, wifiSyncManager.isWifiConnected())
            } else {
                communityRepository.reportNotSpam(item.sender, wifiSyncManager.isWifiConnected())
            }
        }
    }

    fun dismissAllPending(markAsSafe: Boolean = true) = viewModelScope.launch {
        repository.dismissAllPendingReviews(markAsSafe)
    }

    // ─── Spam Bildir ──────────────────────────────────────────────────────────

    fun reportAsSpam(
        number: String,
        messageBody: String = "",
        triggeredRules: List<String> = emptyList()
    ) = viewModelScope.launch {
        repository.addSpam(number, label = "Bildirildi")
        if (consentManager.communityConsent) {
            val safeRules = SpamTokenExtractor.sanitizeRules(triggeredRules)
            communityRepository.reportSpam(number, safeRules, wifiSyncManager.isWifiConnected())
        }
    }

    // ─── SMS Geçmişi ──────────────────────────────────────────────────────────

    fun refreshSmsHistory() = viewModelScope.launch {
        _smsHistory.value = repository.readSmsHistory(context)
    }

    fun markSms(id: Long, sender: String, isSpam: Boolean) = viewModelScope.launch {
        _smsFeedback.value = _smsFeedback.value + (id to isSpam)
        if (isSpam) reportAsSpam(sender)
        else repository.addWhitelist(sender, name = "Güvenilir (işaretlendi)")
    }

    fun markSenderMessages(messages: List<SmsHistoryItem>, isSpam: Boolean) = viewModelScope.launch {
        val updated = _smsFeedback.value.toMutableMap()
        messages.forEach { updated[it.id] = isSpam }
        _smsFeedback.value = updated

        val sender = messages.firstOrNull()?.sender ?: return@launch
        val body   = messages.firstOrNull()?.body ?: ""
        if (isSpam) reportAsSpam(sender, body)
        else repository.addWhitelist(sender, name = "Güvenilir (işaretlendi)")
    }

    fun clearFeedback() { _smsFeedback.value = emptyMap() }

    fun refreshCommunitySummary() = viewModelScope.launch {
        if (consentManager.communityConsent && wifiSyncManager.isWifiConnected()) {
            communityRepository.syncCommunityList(force = true)
            _lastSyncTime.value = consentManager.lastSyncTime
        }
        val count = repository.communityReportCount()
        _communitySummary.value = if (count > 0) "$count topluluk kaydı" else ""
    }
}