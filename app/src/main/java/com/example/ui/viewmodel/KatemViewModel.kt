package com.example.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.crypto.E2eeCryptoManager
import com.example.data.firestore.KatemRepository
import com.example.data.local.AppPreferences
import com.example.data.model.BlockedUserEntry
import com.example.data.model.ChatMessage
import com.example.data.model.ContactEntry
import com.example.data.model.Conversation
import com.example.data.model.DecryptedMessage
import com.example.data.model.DeviceSession
import com.example.data.model.EncryptionKeyRecord
import com.example.data.model.PublicIdEntry
import com.example.data.model.RecoveryDataRecord
import com.example.data.model.UiState
import com.example.data.model.UserNotificationEntry
import com.example.data.model.UserProfile
import com.example.data.remote.HostingerHealthStatus
import com.example.data.remote.HostingerMediaClient
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class FriendSearchState(
    val query: String = "",
    val isLoading: Boolean = false,
    val result: PublicIdEntry? = null,
    val notFound: Boolean = false,
    val error: String? = null
)

data class ActiveChatMeta(
    val conversation: Conversation? = null,
    val peerPublicKey: EncryptionKeyRecord? = null,
    val peerProfile: PublicIdEntry? = null,
    val safetyNumber: String = "",
    val isPeerBlocked: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class KatemViewModel(
    private val repository: KatemRepository,
    private val cryptoManager: E2eeCryptoManager,
    private val preferences: AppPreferences,
    private val hostingerClient: HostingerMediaClient,
    val currentUserId: String
) : ViewModel() {

    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    val isDarkTheme: StateFlow<Boolean> = preferences.darkThemeFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = true
    )

    val hostingerBaseUrl: StateFlow<String> = preferences.hostingerBaseUrlFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ""
    )

    val isZangiOnboardingCompleted: StateFlow<Boolean?> = preferences.zangiOnboardingCompletedFlow(currentUserId)
        .map<Boolean, Boolean?> { it }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null
        )

    val requirePasswordOnLaunch: StateFlow<Boolean> = preferences.requirePasswordOnLaunchFlow(currentUserId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = false
        )

    private val _isSessionUnlocked = MutableStateFlow(false)
    val isSessionUnlocked: StateFlow<Boolean> = _isSessionUnlocked.asStateFlow()

    private val _isManuallyLocked = MutableStateFlow(false)
    val isManuallyLocked: StateFlow<Boolean> = _isManuallyLocked.asStateFlow()

    private val _zangiAuthLoading = MutableStateFlow(false)
    val zangiAuthLoading: StateFlow<Boolean> = _zangiAuthLoading.asStateFlow()

    private val _zangiAuthError = MutableStateFlow<String?>(null)
    val zangiAuthError: StateFlow<String?> = _zangiAuthError.asStateFlow()

    val userProfileState: StateFlow<UiState<UserProfile?>> =
        repository.observeUserProfile(currentUserId).toSafeUiState("تعذر تحميل الملف الشخصي")

    val conversationsState: StateFlow<UiState<List<Conversation>>> =
        repository.observeConversations(currentUserId).toSafeUiState("تعذر تحميل المحادثات")

    val contactsState: StateFlow<UiState<List<ContactEntry>>> =
        repository.observeContacts(currentUserId).toSafeUiState("تعذر تحميل جهات الاتصال")

    val blockedUsersState: StateFlow<UiState<List<BlockedUserEntry>>> =
        repository.observeBlockedUsers(currentUserId).toSafeUiState("تعذر تحميل قائمة الحظر")

    val devicesState: StateFlow<UiState<List<DeviceSession>>> =
        repository.observeDevices(currentUserId).toSafeUiState("تعذر تحميل الأجهزة")

    val notificationsState: StateFlow<UiState<List<UserNotificationEntry>>> =
        repository.observeNotifications(currentUserId).toSafeUiState("تعذر تحميل الإشعارات")

    val recoveryDataState: StateFlow<UiState<RecoveryDataRecord?>> =
        repository.observeRecoveryData(currentUserId).toSafeUiState("تعذر تحميل بيانات الاستعادة")

    private val _friendSearch = MutableStateFlow(FriendSearchState())
    val friendSearch: StateFlow<FriendSearchState> = _friendSearch.asStateFlow()

    private val _hostingerHealth = MutableStateFlow<HostingerHealthStatus?>(null)
    val hostingerHealth: StateFlow<HostingerHealthStatus?> = _hostingerHealth.asStateFlow()

    private val _isUploadingMedia = MutableStateFlow(false)
    val isUploadingMedia: StateFlow<Boolean> = _isUploadingMedia.asStateFlow()

    // Active Chat Selection & Real-time Decryption
    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _peerKeyCache = MutableStateFlow<Map<String, EncryptionKeyRecord>>(emptyMap())
    private val _peerProfileCache = MutableStateFlow<Map<String, PublicIdEntry>>(emptyMap())

    val activeConversation: StateFlow<Conversation?> = _activeConversationId
        .flatMapLatest { convId ->
            if (convId.isNullOrBlank()) flowOf(null)
            else repository.observeConversation(currentUserId, convId).catch { emit(null) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    val activeChatMeta: StateFlow<ActiveChatMeta> = combine(
        activeConversation,
        _peerKeyCache,
        _peerProfileCache,
        blockedUsersState
    ) { conv, keyMap, profileMap, blockedState ->
        if (conv == null) {
            ActiveChatMeta()
        } else {
            val peerUid = conv.peerUid(currentUserId)
            val peerKey = keyMap[peerUid]
            val peerProfile = profileMap[peerUid]
            val myBundle = runCatching { cryptoManager.getPublicKeyBundle(currentUserId) }.getOrNull()
            val safety = if (myBundle != null && peerKey != null) {
                cryptoManager.computeSafetyNumber(myBundle.publicKeyBase64, peerKey.publicKeyBase64)
            } else ""
            val blocked = (blockedState as? UiState.Success)?.data?.any { it.blockedUid == peerUid } == true
            ActiveChatMeta(
                conversation = conv,
                peerPublicKey = peerKey,
                peerProfile = peerProfile,
                safetyNumber = safety,
                isPeerBlocked = blocked
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ActiveChatMeta())

    private val rawActiveMessages: Flow<List<ChatMessage>> = _activeConversationId
        .flatMapLatest { convId ->
            if (convId.isNullOrBlank()) flowOf(emptyList())
            else repository.observeMessages(currentUserId, convId)
        }

    val activeMessagesState: StateFlow<UiState<List<DecryptedMessage>>> = combine(
        rawActiveMessages,
        activeConversation,
        _peerKeyCache
    ) { messages, conv, keyMap ->
        val nowMs = System.currentTimeMillis()
        val peerUid = conv?.peerUid(currentUserId) ?: currentUserId
        val peerPubKey = keyMap[peerUid]?.publicKeyBase64
        val visible = messages.filter { msg ->
            msg.expiresAtEpochMs == 0L || msg.expiresAtEpochMs > nowMs
        }
        visible.map { msg -> decryptSingleMessage(msg, peerPubKey) }
    }.toSafeUiState("تعذر تحميل الرسائل")

    val myPublicFingerprint: String
        get() = runCatching { cryptoManager.getPublicKeyBundle(currentUserId).fingerprint }.getOrDefault("---")

    init {
        initializeAccount()
    }

    fun initializeAccount() {
        viewModelScope.launch {
            _isInitializing.value = true
            _initError.value = null
            val deviceId = preferences.getOrCreateDeviceId()
            repository.ensureUserInitialized(deviceId)
                .onSuccess {
                    // Cache own public key for self-chat decryption immediately
                    loadPeerMetadata(currentUserId, it.publicId)
                }
                .onFailure { err ->
                    _initError.value = err.localizedMessage ?: "تعذر تهيئة الحساب المشفّر."
                }
            _isInitializing.value = false
        }
    }

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch { preferences.setDarkTheme(enabled) }
    }

    fun setRequirePasswordOnLaunch(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setRequirePasswordOnLaunch(currentUserId, enabled)
            _snackbarEvents.tryEmit(
                if (enabled) "تم تفعيل طلب كلمة المرور عند فتح التطبيق."
                else "تم إيقاف طلب كلمة المرور عند فتح التطبيق."
            )
        }
    }

    fun clearZangiAuthError() {
        _zangiAuthError.value = null
    }

    fun lockAccountNow() {
        _zangiAuthError.value = null
        _isSessionUnlocked.value = false
        _isManuallyLocked.value = true
    }

    fun completeZangiRegistration(
        displayName: String,
        bio: String,
        avatarColorIndex: Long,
        password: String
    ) {
        val profile = (userProfileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            _zangiAuthLoading.value = true
            _zangiAuthError.value = null

            val profileRes = repository.updateProfile(
                publicId = profile.publicId,
                displayName = displayName,
                bio = bio,
                avatarUrl = profile.avatarUrl,
                avatarColorIndex = avatarColorIndex
            )
            if (profileRes.isFailure) {
                _zangiAuthError.value = profileRes.exceptionOrNull()?.localizedMessage
                    ?: "تعذر حفظ بيانات الملف الشخصي."
                _zangiAuthLoading.value = false
                return@launch
            }

            val vaultRes = repository.saveRecoveryBackup(password)
            if (vaultRes.isFailure) {
                _zangiAuthError.value = vaultRes.exceptionOrNull()?.localizedMessage
                    ?: "تعذر حفظ كلمة المرور وتشفير المفاتيح."
                _zangiAuthLoading.value = false
                return@launch
            }

            preferences.markZangiOnboardingCompleted(currentUserId, true)
            _isSessionUnlocked.value = true
            _isManuallyLocked.value = false
            _zangiAuthLoading.value = false
            _snackbarEvents.tryEmit("مرحبًا $displayName! رقمك الفريد (${profile.publicId}) جاهز الآن.")
        }
    }

    fun unlockWithZangiPassword(password: String) {
        val profile = (userProfileState.value as? UiState.Success)?.data
        viewModelScope.launch {
            _zangiAuthLoading.value = true
            _zangiAuthError.value = null

            repository.verifyAndRestoreRecoveryBackup(password)
                .onSuccess {
                    preferences.markZangiOnboardingCompleted(currentUserId, true)
                    if (profile != null) {
                        loadPeerMetadata(currentUserId, profile.publicId)
                    }
                    _isSessionUnlocked.value = true
                    _isManuallyLocked.value = false
                    _snackbarEvents.tryEmit("تم تسجيل الدخول وفك تشفير مفاتيحك بنجاح.")
                }
                .onFailure { err ->
                    _zangiAuthError.value = err.localizedMessage ?: "كلمة المرور غير صحيحة."
                }
            _zangiAuthLoading.value = false
        }
    }

    fun resetPasswordAndEnter(newPassword: String) {
        viewModelScope.launch {
            _zangiAuthLoading.value = true
            _zangiAuthError.value = null
            repository.saveRecoveryBackup(newPassword)
                .onSuccess {
                    preferences.markZangiOnboardingCompleted(currentUserId, true)
                    _isSessionUnlocked.value = true
                    _isManuallyLocked.value = false
                    _snackbarEvents.tryEmit("تم تعيين كلمة المرور الجديدة بنجاح.")
                }
                .onFailure { err ->
                    _zangiAuthError.value = err.localizedMessage ?: "تعذر حفظ كلمة المرور الجديدة."
                }
            _zangiAuthLoading.value = false
        }
    }

    fun saveHostingerBaseUrl(url: String) {
        viewModelScope.launch {
            preferences.setHostingerBaseUrl(url)
            _hostingerHealth.value = null
            _snackbarEvents.tryEmit("تم حفظ رابط بوابة Hostinger.")
        }
    }

    fun testHostingerConnection(url: String) {
        viewModelScope.launch {
            _hostingerHealth.value = HostingerHealthStatus(false, "جارٍ فحص الاتصال بخادم Hostinger...")
            _hostingerHealth.value = hostingerClient.checkGatewayHealth(url)
        }
    }

    fun setOnlinePresence(online: Boolean) {
        val profile = (userProfileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            repository.syncOnlinePresence(profile.publicId, online, profile)
        }
    }

    fun updateProfile(displayName: String, bio: String, avatarUrl: String, avatarColorIndex: Long) {
        val profile = (userProfileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            repository.updateProfile(profile.publicId, displayName, bio, avatarUrl, avatarColorIndex)
                .onSuccess { _snackbarEvents.tryEmit("تم تحديث الملف الشخصي بنجاح.") }
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر تحديث الملف الشخصي.") }
        }
    }

    fun updatePrivacySettings(
        hideLastSeen: Boolean,
        showOnlineStatus: Boolean,
        hideNotificationPreview: Boolean,
        screenshotProtection: Boolean,
        blurOnBackground: Boolean,
        disappearingDefaultSec: Long
    ) {
        val profile = (userProfileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            repository.updatePrivacySettings(
                publicId = profile.publicId,
                hideLastSeen = hideLastSeen,
                showOnlineStatus = showOnlineStatus,
                hideNotificationPreview = hideNotificationPreview,
                screenshotProtection = screenshotProtection,
                blurOnBackground = blurOnBackground,
                disappearingDefaultSec = disappearingDefaultSec
            )
                .onSuccess { _snackbarEvents.tryEmit("تم حفظ إعدادات الخصوصية.") }
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر حفظ الإعدادات.") }
        }
    }

    // -------------------------------------------------------------------------
    // Friend Discovery, Contacts & Conversation Creation
    // -------------------------------------------------------------------------
    fun updateSearchQuery(query: String) {
        _friendSearch.value = _friendSearch.value.copy(query = query, error = null, notFound = false)
    }

    fun searchFriendByPublicId(overrideInput: String? = null) {
        val raw = (overrideInput ?: _friendSearch.value.query).trim()
        if (raw.isBlank()) {
            _friendSearch.value = _friendSearch.value.copy(error = "أدخل معرّف المستخدم أو رابط الدعوة أولًا.")
            return
        }
        viewModelScope.launch {
            _friendSearch.value = FriendSearchState(query = raw, isLoading = true)
            repository.findUserByPublicId(raw)
                .onSuccess { entry ->
                    if (entry == null) {
                        _friendSearch.value = FriendSearchState(query = raw, notFound = true)
                    } else {
                        _friendSearch.value = FriendSearchState(query = entry.publicId, result = entry)
                    }
                }
                .onFailure { err ->
                    _friendSearch.value = FriendSearchState(
                        query = raw,
                        error = err.localizedMessage ?: "تعذر البحث عن المعرّف."
                    )
                }
        }
    }

    fun clearFriendSearch() {
        _friendSearch.value = FriendSearchState()
    }

    fun saveFoundContact(peer: PublicIdEntry) {
        viewModelScope.launch {
            repository.saveContact(peer)
                .onSuccess { _snackbarEvents.tryEmit("تم حفظ ${peer.displayName} في جهات الاتصال.") }
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر حفظ جهة الاتصال.") }
        }
    }

    fun removeContact(contactUid: String) {
        viewModelScope.launch {
            repository.removeContact(contactUid)
                .onSuccess { _snackbarEvents.tryEmit("تم حذف جهة الاتصال.") }
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر حذف جهة الاتصال.") }
        }
    }

    fun startConversationWithPeer(peer: PublicIdEntry, onOpened: (String) -> Unit) {
        val profile = (userProfileState.value as? UiState.Success)?.data
        if (profile == null) {
            _snackbarEvents.tryEmit("جارٍ تهيئة حسابك، انتظر لحظة...")
            return
        }
        viewModelScope.launch {
            repository.getOrCreateConversation(profile, peer)
                .onSuccess { convId ->
                    openConversation(convId, peer.ownerUid, peer.publicId)
                    onOpened(convId)
                }
                .onFailure { err ->
                    _snackbarEvents.tryEmit(err.localizedMessage ?: "تعذر فتح المحادثة.")
                }
        }
    }

    fun startConversationWithContact(contact: ContactEntry, onOpened: (String) -> Unit) {
        val peer = PublicIdEntry(
            id = contact.contactPublicId,
            publicId = contact.contactPublicId,
            ownerUid = contact.contactUid,
            displayName = contact.displayName,
            bio = contact.bio,
            avatarUrl = contact.avatarUrl,
            avatarColorIndex = contact.avatarColorIndex
        )
        startConversationWithPeer(peer, onOpened)
    }

    fun openSavedNotesChat(onOpened: (String) -> Unit) {
        val profile = (userProfileState.value as? UiState.Success)?.data ?: return
        val selfPeer = PublicIdEntry(
            id = profile.publicId,
            publicId = profile.publicId,
            ownerUid = profile.userId,
            displayName = profile.displayName,
            bio = profile.bio,
            avatarUrl = profile.avatarUrl,
            avatarColorIndex = profile.avatarColorIndex
        )
        startConversationWithPeer(selfPeer, onOpened)
    }

    fun openConversation(conversationId: String, peerUid: String? = null, peerPublicId: String? = null) {
        _activeConversationId.value = conversationId
        viewModelScope.launch {
            if (!peerUid.isNullOrBlank()) {
                loadPeerMetadata(peerUid, peerPublicId)
            } else {
                val conv = (conversationsState.value as? UiState.Success)?.data
                    ?.firstOrNull { it.id == conversationId }
                if (conv != null) {
                    loadPeerMetadata(conv.peerUid(currentUserId), conv.peerPublicId(currentUserId))
                }
            }
        }
    }

    fun closeConversation() {
        val currentConv = _activeConversationId.value
        if (!currentConv.isNullOrBlank()) {
            viewModelScope.launch { repository.setTypingIndicator(currentConv, false) }
        }
        _activeConversationId.value = null
    }

    private suspend fun loadPeerMetadata(peerUid: String, peerPublicId: String?) {
        repository.getPeerPublicKey(peerUid).onSuccess { key ->
            if (key != null) {
                _peerKeyCache.value = _peerKeyCache.value + (peerUid to key)
            }
        }
        if (!peerPublicId.isNullOrBlank()) {
            repository.findUserByPublicId(peerPublicId).onSuccess { entry ->
                if (entry != null) {
                    _peerProfileCache.value = _peerProfileCache.value + (peerUid to entry)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Chat Messaging, Editing, Deleting, Typing & Media Uploads
    // -------------------------------------------------------------------------
    fun sendTextMessage(
        plaintext: String,
        replyTo: DecryptedMessage? = null
    ) {
        val conv = activeConversation.value ?: return
        val profile = (userProfileState.value as? UiState.Success)?.data ?: return
        if (activeChatMeta.value.isPeerBlocked) {
            _snackbarEvents.tryEmit("لا يمكنك الإرسال لمستخدم محظور. قم بإلغاء الحظر أولًا.")
            return
        }
        viewModelScope.launch {
            val peerUid = conv.peerUid(currentUserId)
            var peerKey = _peerKeyCache.value[peerUid]?.publicKeyBase64
            if (peerKey == null) {
                loadPeerMetadata(peerUid, conv.peerPublicId(currentUserId))
                peerKey = _peerKeyCache.value[peerUid]?.publicKeyBase64
            }
            if (peerKey == null) {
                _snackbarEvents.tryEmit("تعذر العثور على مفتاح التشفير العام للطرف الآخر.")
                return@launch
            }
            repository.sendEncryptedTextMessage(
                conversation = conv,
                myProfile = profile,
                peerPublicKeyBase64 = peerKey,
                plaintext = plaintext,
                replyToMessageId = replyTo?.raw?.id.orEmpty(),
                replyPreviewPlaintext = replyTo?.plaintext.orEmpty()
            ).onFailure { err ->
                _snackbarEvents.tryEmit(err.localizedMessage ?: "تعذر إرسال الرسالة.")
            }
        }
    }

    fun editMessage(messageId: String, newPlaintext: String) {
        val conv = activeConversation.value ?: return
        viewModelScope.launch {
            val peerUid = conv.peerUid(currentUserId)
            val peerKey = _peerKeyCache.value[peerUid]?.publicKeyBase64
            if (peerKey == null) {
                _snackbarEvents.tryEmit("مفتاح التشفير غير جاهز.")
                return@launch
            }
            repository.editEncryptedMessage(conv.id, messageId, peerKey, newPlaintext)
                .onSuccess { _snackbarEvents.tryEmit("تم تعديل الرسالة المشفّرة.") }
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر تعديل الرسالة.") }
        }
    }

    fun deleteMessage(messageId: String) {
        val conv = activeConversation.value ?: return
        viewModelScope.launch {
            repository.deleteMessage(conv.id, messageId)
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر حذف الرسالة.") }
        }
    }

    fun markIncomingMessagesAsRead(messages: List<DecryptedMessage>) {
        val convId = _activeConversationId.value ?: return
        val unreadIds = messages
            .filter { it.raw.senderUid != currentUserId && it.raw.deliveryStatus != "read" }
            .map { it.raw.id }
        if (unreadIds.isEmpty()) return
        viewModelScope.launch {
            repository.markMessagesRead(convId, unreadIds)
        }
    }

    fun updateTypingState(isTyping: Boolean) {
        val convId = _activeConversationId.value ?: return
        viewModelScope.launch {
            repository.setTypingIndicator(convId, isTyping)
        }
    }

    fun sendSelectedMediaUri(uri: Uri, messageType: String, fallbackMime: String) {
        val conv = activeConversation.value ?: return
        val profile = (userProfileState.value as? UiState.Success)?.data ?: return
        val baseUrl = hostingerBaseUrl.value
        viewModelScope.launch {
            _isUploadingMedia.value = true
            hostingerClient.uploadUriToHostinger(baseUrl, conv.id, uri, fallbackMime)
                .onSuccess { uploaded ->
                    repository.sendHostingerMediaMessage(conv, profile, messageType, uploaded)
                        .onSuccess { _snackbarEvents.tryEmit("تم رفع وإرسال الملف عبر Hostinger بنجاح.") }
                        .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تم الرفع لكن تعذر حفظ الرسالة.") }
                }
                .onFailure { err ->
                    _snackbarEvents.tryEmit(err.localizedMessage ?: "تعذر رفع الملف إلى Hostinger.")
                }
            _isUploadingMedia.value = false
        }
    }

    fun sendRecordedVoiceFile(file: File, durationSec: Long) {
        val conv = activeConversation.value ?: return
        val profile = (userProfileState.value as? UiState.Success)?.data ?: return
        val baseUrl = hostingerBaseUrl.value
        viewModelScope.launch {
            _isUploadingMedia.value = true
            hostingerClient.uploadLocalFileToHostinger(baseUrl, conv.id, file, "audio/mp4")
                .onSuccess { uploaded ->
                    repository.sendHostingerMediaMessage(conv, profile, "voice", uploaded, durationSec)
                        .onSuccess { _snackbarEvents.tryEmit("تم إرسال الرسالة الصوتية عبر Hostinger.") }
                        .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر حفظ الرسالة الصوتية.") }
                }
                .onFailure { err ->
                    _snackbarEvents.tryEmit(err.localizedMessage ?: "تعذر رفع الرسالة الصوتية إلى Hostinger.")
                }
            _isUploadingMedia.value = false
        }
    }

    suspend fun downloadAuthorizedMedia(downloadUrl: String, fileName: String): Result<File> {
        return hostingerClient.downloadAuthorizedToCache(downloadUrl, fileName)
    }

    fun toggleArchiveConversation(conversation: Conversation) {
        viewModelScope.launch {
            repository.toggleArchiveConversation(conversation)
                .onSuccess {
                    val msg = if (conversation.isArchivedFor(currentUserId)) "تم إلغاء أرشفة المحادثة." else "تمت أرشفة المحادثة."
                    _snackbarEvents.tryEmit(msg)
                }
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر تحديث الأرشفة.") }
        }
    }

    fun deleteConversation(conversation: Conversation, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteConversationForMe(conversation)
                .onSuccess {
                    _snackbarEvents.tryEmit("تم حذف المحادثة.")
                    onDeleted()
                }
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر حذف المحادثة.") }
        }
    }

    fun setConversationDisappearingTimer(conversationId: String, seconds: Long) {
        viewModelScope.launch {
            repository.updateConversationDisappearingTimer(conversationId, seconds)
                .onSuccess { _snackbarEvents.tryEmit("تم تحديث مؤقت الرسائل ذاتية الاختفاء.") }
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر تحديث المؤقت.") }
        }
    }

    // -------------------------------------------------------------------------
    // Recovery Vault, Blocking, Reporting & Notifications
    // -------------------------------------------------------------------------
    fun generateRecoveryPassphrase(): String = cryptoManager.generateStrongPassphrase()

    fun saveRecoveryVault(passphrase: String) {
        viewModelScope.launch {
            repository.saveRecoveryBackup(passphrase)
                .onSuccess { _snackbarEvents.tryEmit("تم تشفير وحفظ مفتاح الاستعادة بنجاح.") }
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر حفظ مفتاح الاستعادة.") }
        }
    }

    fun restoreRecoveryVault(passphrase: String) {
        viewModelScope.launch {
            repository.verifyAndRestoreRecoveryBackup(passphrase)
                .onSuccess { fp -> _snackbarEvents.tryEmit("تمت استعادة مفتاح التشفير بنجاح! البصمة: ${fp.take(9)}...") }
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر استعادة المفتاح.") }
        }
    }

    fun blockUser(uid: String, publicId: String, displayName: String) {
        viewModelScope.launch {
            repository.blockUser(uid, publicId, displayName)
                .onSuccess { _snackbarEvents.tryEmit("تم حظر $displayName.") }
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر حظر المستخدم.") }
        }
    }

    fun unblockUser(uid: String) {
        viewModelScope.launch {
            repository.unblockUser(uid)
                .onSuccess { _snackbarEvents.tryEmit("تم إلغاء الحظر.") }
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر إلغاء الحظر.") }
        }
    }

    fun reportUser(
        reportedUid: String,
        reportedPublicId: String,
        conversationId: String,
        reason: String,
        details: String
    ) {
        viewModelScope.launch {
            repository.submitReport(reportedUid, reportedPublicId, conversationId, reason, details)
                .onSuccess { _snackbarEvents.tryEmit("تم إرسال البلاغ بأمان للمراجعة.") }
                .onFailure { _snackbarEvents.tryEmit(it.localizedMessage ?: "تعذر إرسال البلاغ.") }
        }
    }

    fun markNotificationRead(notificationId: String) {
        viewModelScope.launch { repository.markNotificationRead(notificationId) }
    }

    // -------------------------------------------------------------------------
    // Internal Helpers (E2EE Decryption & Safe Two-Tier Flow Error Mapping)
    // -------------------------------------------------------------------------
    private fun decryptSingleMessage(msg: ChatMessage, peerPublicKeyBase64: String?): DecryptedMessage {
        if (msg.deleted) {
            return DecryptedMessage(msg, "تم حذف هذه الرسالة", "", true)
        }
        if (msg.messageType != "text") {
            val label = msg.mediaFileName.ifBlank {
                when (msg.messageType) {
                    "image" -> "صورة"
                    "video" -> "فيديو"
                    "voice" -> "رسالة صوتية"
                    else -> "مستند"
                }
            }
            return DecryptedMessage(msg, label, "", true)
        }
        if (peerPublicKeyBase64.isNullOrBlank() || msg.ciphertext.isBlank() || msg.iv.isBlank()) {
            return DecryptedMessage(msg, "جارٍ التحقق من مفاتيح التشفير...", "", false)
        }
        val decrypted = cryptoManager.decryptMessage(
            myUid = currentUserId,
            peerPublicKeyBase64 = peerPublicKeyBase64,
            conversationId = msg.conversationId,
            ciphertextBase64 = msg.ciphertext,
            ivBase64 = msg.iv
        )
        val replyPlain = if (msg.replyPreviewCiphertext.isNotBlank() && msg.replyPreviewIv.isNotBlank()) {
            cryptoManager.decryptMessage(
                myUid = currentUserId,
                peerPublicKeyBase64 = peerPublicKeyBase64,
                conversationId = msg.conversationId,
                ciphertextBase64 = msg.replyPreviewCiphertext,
                ivBase64 = msg.replyPreviewIv
            ).getOrDefault("رسالة مشفّرة")
        } else ""

        return if (decrypted.isSuccess) {
            DecryptedMessage(msg, decrypted.getOrThrow(), replyPlain, true)
        } else {
            DecryptedMessage(msg, "تعذر فك التشفير — استخدم مفتاح الاستعادة إن غيّرت الجهاز.", replyPlain, false)
        }
    }

    private fun <T> Flow<T>.toSafeUiState(defaultErrorMsg: String): StateFlow<UiState<T>> =
        this.map<T, UiState<T>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Stream failure: $defaultErrorMsg", rootError)
                emit(UiState.Error(rootError.localizedMessage ?: defaultErrorMsg))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    companion object {
        private const val TAG = "KatemVM"
        private const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
