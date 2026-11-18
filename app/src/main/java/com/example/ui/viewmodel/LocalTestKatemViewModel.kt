package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.crypto.E2eeCryptoManager
import com.example.data.crypto.WrappedRecoveryBlob
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
import com.google.firebase.Timestamp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * Purely local, device-only sandbox ViewModel for instant testing of the full Zangi-style
 * Katem experience (Unique ID, Name + Password vault, real ECDH-P256 + HKDF-SHA256 + AES-256-GCM
 * E2EE messaging, QR codes, voice notes, media selection, privacy toggles, and Hostinger checks)
 * without touching Cloud Firestore until the user signs in via Google for Cloud Sync.
 */
class LocalTestKatemViewModel(
    private val appContext: Context,
    private val cryptoManager: E2eeCryptoManager,
    private val preferences: AppPreferences,
    private val hostingerClient: HostingerMediaClient
) : ViewModel() {

    val localUserId: String = "local_katem_user"
    private val testPeerUid: String = "local_katem_peer_demo"
    private val testPeerPublicId: String = "KTM-100200A"

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    val isDarkTheme: StateFlow<Boolean> = preferences.darkThemeFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = true
    )

    val hostingerBaseUrl: StateFlow<String> = preferences.hostingerBaseUrlFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = ""
    )

    private val _hostingerHealth = MutableStateFlow<HostingerHealthStatus?>(null)
    val hostingerHealth: StateFlow<HostingerHealthStatus?> = _hostingerHealth.asStateFlow()

    private val _isUploadingMedia = MutableStateFlow(false)
    val isUploadingMedia: StateFlow<Boolean> = _isUploadingMedia.asStateFlow()

    private val _zangiRegistrationCompleted = MutableStateFlow(false)
    val zangiRegistrationCompleted: StateFlow<Boolean> = _zangiRegistrationCompleted.asStateFlow()

    private val _isSessionUnlocked = MutableStateFlow(false)
    val isSessionUnlocked: StateFlow<Boolean> = _isSessionUnlocked.asStateFlow()

    private val _requirePasswordOnLaunch = MutableStateFlow(false)
    val requirePasswordOnLaunch: StateFlow<Boolean> = _requirePasswordOnLaunch.asStateFlow()

    private val _zangiAuthLoading = MutableStateFlow(false)
    val zangiAuthLoading: StateFlow<Boolean> = _zangiAuthLoading.asStateFlow()

    private val _zangiAuthError = MutableStateFlow<String?>(null)
    val zangiAuthError: StateFlow<String?> = _zangiAuthError.asStateFlow()

    private val _userProfile = MutableStateFlow(createInitialProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    private val _recoveryRecord = MutableStateFlow<RecoveryDataRecord?>(null)
    val recoveryRecord: StateFlow<RecoveryDataRecord?> = _recoveryRecord.asStateFlow()

    private val _contacts = MutableStateFlow<List<ContactEntry>>(emptyList())
    val contactsState: StateFlow<UiState<List<ContactEntry>>> = combine(_contacts) { (list) ->
        UiState.Success(list)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Success(emptyList()))

    private val _blockedUsers = MutableStateFlow<List<BlockedUserEntry>>(emptyList())
    val blockedUsers: StateFlow<List<BlockedUserEntry>> = _blockedUsers.asStateFlow()

    private val _devices = MutableStateFlow(
        listOf(
            DeviceSession(
                id = "local_device",
                userId = localUserId,
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "جهاز الاختبار" },
                platform = "Android ${Build.VERSION.RELEASE} (محلي)",
                appVersion = "1.0",
                lastActiveAt = Timestamp.now(),
                createdAt = Timestamp.now(),
                updatedAt = Timestamp.now()
            )
        )
    )
    val devices: StateFlow<List<DeviceSession>> = _devices.asStateFlow()

    private val _notifications = MutableStateFlow(
        listOf(
            UserNotificationEntry(
                id = "local_welcome_notif",
                userId = localUserId,
                conversationId = "",
                senderUid = testPeerUid,
                senderPublicId = testPeerPublicId,
                senderName = "صديق تجريبي (كاتم)",
                type = "security_alert",
                previewHidden = false,
                read = false,
                createdAt = Timestamp.now(),
                updatedAt = Timestamp.now()
            )
        )
    )
    val notifications: StateFlow<List<UserNotificationEntry>> = _notifications.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversationsState: StateFlow<UiState<List<Conversation>>> = combine(_conversations) { (list) ->
        UiState.Success(
            list.filterNot { it.isDeletedFor(localUserId) }
                .sortedByDescending { it.lastMessageAt ?: it.updatedAt ?: Timestamp(0, 0) }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Success(emptyList()))

    private val _messagesByConv = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private val _activeConversationId = MutableStateFlow<String?>(null)

    private val _friendSearch = MutableStateFlow(FriendSearchState())
    val friendSearch: StateFlow<FriendSearchState> = _friendSearch.asStateFlow()

    private val peerDirectory = mutableMapOf<String, PublicIdEntry>()
    private val keyDirectory = mutableMapOf<String, EncryptionKeyRecord>()
    private val localMediaFiles = mutableMapOf<String, File>()

    val myPublicFingerprint: String
        get() = runCatching { cryptoManager.getPublicKeyBundle(localUserId).fingerprint }.getOrDefault("---")

    val activeChatMeta: StateFlow<ActiveChatMeta> = combine(
        _activeConversationId,
        _conversations,
        _blockedUsers
    ) { convId, convs, blocked ->
        val conv = convs.firstOrNull { it.id == convId }
        if (conv == null) {
            ActiveChatMeta()
        } else {
            val peerUid = conv.peerUid(localUserId)
            val peerKey = keyDirectory[peerUid]
            val peerProfile = peerDirectory.values.firstOrNull { it.ownerUid == peerUid }
            val myBundle = cryptoManager.getPublicKeyBundle(localUserId)
            val safety = if (peerKey != null) {
                cryptoManager.computeSafetyNumber(myBundle.publicKeyBase64, peerKey.publicKeyBase64)
            } else ""
            ActiveChatMeta(
                conversation = conv,
                peerPublicKey = peerKey,
                peerProfile = peerProfile,
                safetyNumber = safety,
                isPeerBlocked = blocked.any { it.blockedUid == peerUid }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), ActiveChatMeta())

    val activeMessagesState: StateFlow<UiState<List<DecryptedMessage>>> = combine(
        _activeConversationId,
        _messagesByConv,
        _conversations
    ) { convId, map, convs ->
        val conv = convs.firstOrNull { it.id == convId }
        val raw = if (convId == null) emptyList() else map[convId].orEmpty()
        val nowMs = System.currentTimeMillis()
        val peerUid = conv?.peerUid(localUserId) ?: localUserId
        val peerPubKey = keyDirectory[peerUid]?.publicKeyBase64
        val decrypted = raw
            .filter { it.expiresAtEpochMs == 0L || it.expiresAtEpochMs > nowMs }
            .map { msg -> decryptSingleMessage(msg, peerPubKey) }
        UiState.Success(decrypted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Success(emptyList()))

    init {
        setupLocalE2eeKeysAndDemoPeer()
    }

    private fun createInitialProfile(): UserProfile {
        val random = SecureRandom()
        val digits = (0 until 6).map { random.nextInt(10) }.joinToString("")
        val letters = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val check = letters[random.nextInt(letters.length)]
        val generatedId = "KTM-$digits$check"
        return UserProfile(
            id = localUserId,
            userId = localUserId,
            publicId = generatedId,
            displayName = "",
            bio = "",
            avatarUrl = "",
            avatarColorIndex = random.nextInt(6).toLong(),
            hideLastSeen = false,
            showOnlineStatus = true,
            hideNotificationPreview = true,
            screenshotProtection = false,
            blurOnBackground = true,
            disappearingDefaultSec = 0L,
            lastSeenAt = Timestamp.now(),
            createdAt = Timestamp.now(),
            updatedAt = Timestamp.now()
        )
    }

    private fun setupLocalE2eeKeysAndDemoPeer() {
        val myBundle = cryptoManager.getPublicKeyBundle(localUserId)
        val peerBundle = cryptoManager.getPublicKeyBundle(testPeerUid)

        keyDirectory[localUserId] = EncryptionKeyRecord(
            id = localUserId,
            userId = localUserId,
            publicKeyBase64 = myBundle.publicKeyBase64,
            fingerprint = myBundle.fingerprint,
            algorithm = myBundle.algorithm,
            keyVersion = 1L,
            createdAt = Timestamp.now(),
            updatedAt = Timestamp.now()
        )
        keyDirectory[testPeerUid] = EncryptionKeyRecord(
            id = testPeerUid,
            userId = testPeerUid,
            publicKeyBase64 = peerBundle.publicKeyBase64,
            fingerprint = peerBundle.fingerprint,
            algorithm = peerBundle.algorithm,
            keyVersion = 1L,
            createdAt = Timestamp.now(),
            updatedAt = Timestamp.now()
        )

        val testPeerEntry = PublicIdEntry(
            id = testPeerPublicId,
            publicId = testPeerPublicId,
            ownerUid = testPeerUid,
            displayName = "صديق تجريبي (كاتم)",
            bio = "حساب محلي لاختبار التشفير الفوري E2EE والرسائل والوسائط",
            avatarUrl = "",
            avatarColorIndex = 2L,
            onlineVisible = true,
            online = true,
            lastSeenVisible = true,
            lastSeenAt = Timestamp.now(),
            createdAt = Timestamp.now(),
            updatedAt = Timestamp.now()
        )
        peerDirectory[testPeerPublicId] = testPeerEntry

        _contacts.value = listOf(
            ContactEntry(
                id = testPeerUid,
                userId = localUserId,
                contactUid = testPeerUid,
                contactPublicId = testPeerPublicId,
                displayName = testPeerEntry.displayName,
                bio = testPeerEntry.bio,
                avatarUrl = "",
                avatarColorIndex = testPeerEntry.avatarColorIndex,
                createdAt = Timestamp.now(),
                updatedAt = Timestamp.now()
            )
        )
    }

    // -------------------------------------------------------------------------
    // Zangi Registration, Password Lock & Unlock (Real PBKDF2 + AES-256-GCM)
    // -------------------------------------------------------------------------
    fun generatePassword(): String = cryptoManager.generateStrongPassphrase()

    fun clearZangiAuthError() {
        _zangiAuthError.value = null
    }

    fun completeZangiRegistration(
        displayName: String,
        bio: String,
        avatarColorIndex: Long,
        password: String
    ) {
        viewModelScope.launch {
            _zangiAuthLoading.value = true
            _zangiAuthError.value = null
            try {
                val updated = _userProfile.value.copy(
                    displayName = displayName.trim(),
                    bio = bio.trim(),
                    avatarColorIndex = avatarColorIndex,
                    updatedAt = Timestamp.now()
                )
                _userProfile.value = updated
                peerDirectory[updated.publicId] = PublicIdEntry(
                    id = updated.publicId,
                    publicId = updated.publicId,
                    ownerUid = localUserId,
                    displayName = updated.displayName,
                    bio = updated.bio,
                    avatarColorIndex = updated.avatarColorIndex,
                    onlineVisible = true,
                    online = true,
                    lastSeenVisible = true,
                    lastSeenAt = Timestamp.now(),
                    createdAt = Timestamp.now(),
                    updatedAt = Timestamp.now()
                )

                val blob: WrappedRecoveryBlob = cryptoManager.createRecoveryVaultBlob(localUserId, password)
                _recoveryRecord.value = RecoveryDataRecord(
                    id = "primary",
                    userId = localUserId,
                    encryptedBlob = blob.encryptedBlobBase64,
                    saltBase64 = blob.saltBase64,
                    ivBase64 = blob.ivBase64,
                    kdfIterations = blob.kdfIterations.toLong(),
                    keyVersion = 1L,
                    failedAttempts = 0L,
                    lockedUntilEpochMs = 0L,
                    createdAt = Timestamp.now(),
                    updatedAt = Timestamp.now()
                )

                seedInitialEncryptedDemoChat(updated)

                _zangiRegistrationCompleted.value = true
                _isSessionUnlocked.value = true
                _snackbarEvents.tryEmit("مرحبًا ${updated.displayName}! رقمك الفريد (${updated.publicId}) جاهز للاختبار.")
            } catch (e: Exception) {
                _zangiAuthError.value = e.localizedMessage ?: "تعذر إكمال التسجيل."
            } finally {
                _zangiAuthLoading.value = false
            }
        }
    }

    private fun seedInitialEncryptedDemoChat(profile: UserProfile) {
        if (_conversations.value.isNotEmpty()) return
        val convId = "local_conv_demo_peer"
        val now = Timestamp.now()
        val conv = Conversation(
            id = convId,
            memberUids = listOf(localUserId, testPeerUid),
            member1Uid = localUserId,
            member1PublicId = profile.publicId,
            member1Name = profile.displayName,
            member1Avatar = "",
            member1ColorIndex = profile.avatarColorIndex,
            member2Uid = testPeerUid,
            member2PublicId = testPeerPublicId,
            member2Name = "صديق تجريبي (كاتم)",
            member2Avatar = "",
            member2ColorIndex = 2L,
            lastMessageLabel = "رسالة مشفّرة من الطرف إلى الطرف",
            lastMessageType = "text",
            lastMessageSenderUid = testPeerUid,
            lastMessageAt = now,
            createdByUid = localUserId,
            createdAt = now,
            updatedAt = now
        )
        _conversations.value = listOf(conv)

        // Encrypt welcome message with REAL ECDH (peerPriv + myPub -> AES-256-GCM)
        val myPubKey = keyDirectory[localUserId]!!.publicKeyBase64
        val welcomePlain = "أهلًا بك يا ${profile.displayName} في كاتم! هذه المحادثة مشفّرة فعليًا بخوارزمية ECDH-P256 + AES-256-GCM. جرّب إرسال رسالة أو رد أو تعديل أو تسجيل صوتي الآن."
        val enc = cryptoManager.encryptMessage(testPeerUid, myPubKey, convId, welcomePlain)
        val welcomeMsg = ChatMessage(
            id = "msg_welcome_${UUID.randomUUID().toString().take(8)}",
            conversationId = convId,
            memberUids = conv.memberUids,
            senderUid = testPeerUid,
            senderPublicId = testPeerPublicId,
            messageType = "text",
            ciphertext = enc.ciphertextBase64,
            iv = enc.ivBase64,
            deliveryStatus = "delivered",
            createdAt = now,
            updatedAt = now
        )
        _messagesByConv.value = mapOf(convId to listOf(welcomeMsg))
    }

    fun unlockWithZangiPassword(password: String) {
        viewModelScope.launch {
            _zangiAuthLoading.value = true
            _zangiAuthError.value = null
            val rec = _recoveryRecord.value
            if (rec == null) {
                _zangiAuthError.value = "لا توجد كلمة مرور محفوظة."
                _zangiAuthLoading.value = false
                return@launch
            }
            val nowMs = System.currentTimeMillis()
            if (rec.lockedUntilEpochMs > nowMs) {
                val waitSec = ((rec.lockedUntilEpochMs - nowMs) / 1000L).coerceAtLeast(1L)
                _zangiAuthError.value = "تم إيقاف المحاولات مؤقتًا. انتظر $waitSec ثانية."
                _zangiAuthLoading.value = false
                return@launch
            }
            val restored = cryptoManager.restoreFromRecoveryVaultBlob(
                uid = localUserId,
                passphrase = password,
                encryptedBlobBase64 = rec.encryptedBlob,
                saltBase64 = rec.saltBase64,
                ivBase64 = rec.ivBase64,
                kdfIterations = rec.kdfIterations.toInt()
            )
            if (restored.isSuccess) {
                _recoveryRecord.value = rec.copy(failedAttempts = 0L, lockedUntilEpochMs = 0L, updatedAt = Timestamp.now())
                _isSessionUnlocked.value = true
                _snackbarEvents.tryEmit("تم فتح حسابك بنجاح!")
            } else {
                val nextAttempts = rec.failedAttempts + 1L
                val lockUntil = if (nextAttempts >= 3L) nowMs + (nextAttempts * 30_000L) else 0L
                _recoveryRecord.value = rec.copy(failedAttempts = nextAttempts, lockedUntilEpochMs = lockUntil)
                _zangiAuthError.value = "كلمة المرور غير صحيحة (محاولة $nextAttempts)."
            }
            _zangiAuthLoading.value = false
        }
    }

    fun resetPasswordAndEnter(newPassword: String) {
        viewModelScope.launch {
            val blob = cryptoManager.createRecoveryVaultBlob(localUserId, newPassword)
            _recoveryRecord.value = RecoveryDataRecord(
                id = "primary",
                userId = localUserId,
                encryptedBlob = blob.encryptedBlobBase64,
                saltBase64 = blob.saltBase64,
                ivBase64 = blob.ivBase64,
                kdfIterations = blob.kdfIterations.toLong(),
                keyVersion = 1L,
                failedAttempts = 0L,
                lockedUntilEpochMs = 0L,
                createdAt = Timestamp.now(),
                updatedAt = Timestamp.now()
            )
            _zangiRegistrationCompleted.value = true
            _isSessionUnlocked.value = true
            _snackbarEvents.tryEmit("تم تعيين كلمة المرور الجديدة بنجاح.")
        }
    }

    fun lockAccountNow() {
        _zangiAuthError.value = null
        _isSessionUnlocked.value = false
    }

    fun setRequirePasswordOnLaunch(enabled: Boolean) {
        _requirePasswordOnLaunch.value = enabled
        _snackbarEvents.tryEmit(
            if (enabled) "تم تفعيل طلب كلمة المرور عند فتح التطبيق."
            else "تم إيقاف طلب كلمة المرور."
        )
    }

    // -------------------------------------------------------------------------
    // Profile, Privacy, Preferences & Hostinger Health
    // -------------------------------------------------------------------------
    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch { preferences.setDarkTheme(enabled) }
    }

    fun updateProfile(displayName: String, bio: String, avatarUrl: String, avatarColorIndex: Long) {
        val updated = _userProfile.value.copy(
            displayName = displayName.trim().ifBlank { _userProfile.value.publicId },
            bio = bio.trim(),
            avatarUrl = avatarUrl.trim(),
            avatarColorIndex = avatarColorIndex,
            updatedAt = Timestamp.now()
        )
        _userProfile.value = updated
        _snackbarEvents.tryEmit("تم تحديث الملف الشخصي.")
    }

    fun updatePrivacySettings(
        hideLastSeen: Boolean,
        showOnlineStatus: Boolean,
        hideNotificationPreview: Boolean,
        screenshotProtection: Boolean,
        blurOnBackground: Boolean,
        disappearingDefaultSec: Long
    ) {
        _userProfile.value = _userProfile.value.copy(
            hideLastSeen = hideLastSeen,
            showOnlineStatus = showOnlineStatus,
            hideNotificationPreview = hideNotificationPreview,
            screenshotProtection = screenshotProtection,
            blurOnBackground = blurOnBackground,
            disappearingDefaultSec = disappearingDefaultSec,
            updatedAt = Timestamp.now()
        )
        _snackbarEvents.tryEmit("تم حفظ إعدادات الخصوصية.")
    }

    fun saveRecoveryVault(passphrase: String) {
        if (passphrase.length < 8) {
            _snackbarEvents.tryEmit("كلمة المرور يجب أن تكون 8 أحرف على الأقل.")
            return
        }
        resetPasswordAndEnter(passphrase)
    }

    fun restoreRecoveryVault(passphrase: String) {
        unlockWithZangiPassword(passphrase)
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

    // -------------------------------------------------------------------------
    // Friends & Conversations
    // -------------------------------------------------------------------------
    fun updateSearchQuery(query: String) {
        _friendSearch.value = _friendSearch.value.copy(query = query, notFound = false, error = null)
    }

    fun clearFriendSearch() {
        _friendSearch.value = FriendSearchState()
    }

    fun searchFriendByPublicId(overrideInput: String? = null) {
        val raw = (overrideInput ?: _friendSearch.value.query).trim()
        val cleaned = raw
            .removePrefix("katem://add/")
            .removePrefix("https://katem.app/add/")
            .trim()
            .uppercase()
        if (cleaned.isBlank()) {
            _friendSearch.value = _friendSearch.value.copy(error = "أدخل معرّف المستخدم أو رابط الدعوة أولًا.")
            return
        }
        val match = peerDirectory[cleaned]
        _friendSearch.value = FriendSearchState(
            query = cleaned,
            isLoading = false,
            result = match,
            notFound = match == null,
            error = if (match == null) "في وضع الاختبار المحلي يمكنك البحث عن الصديق التجريبي: $testPeerPublicId أو رقمك الشخصي." else null
        )
    }

    fun saveFoundContact(peer: PublicIdEntry) {
        if (_contacts.value.any { it.contactUid == peer.ownerUid }) {
            _snackbarEvents.tryEmit("جهة الاتصال محفوظة بالفعل.")
            return
        }
        _contacts.value = _contacts.value + ContactEntry(
            id = peer.ownerUid,
            userId = localUserId,
            contactUid = peer.ownerUid,
            contactPublicId = peer.publicId,
            displayName = peer.displayName,
            bio = peer.bio,
            avatarColorIndex = peer.avatarColorIndex,
            createdAt = Timestamp.now(),
            updatedAt = Timestamp.now()
        )
        _snackbarEvents.tryEmit("تمت إضافة ${peer.displayName} إلى جهات الاتصال.")
    }

    fun removeContact(contactUid: String) {
        _contacts.value = _contacts.value.filterNot { it.contactUid == contactUid }
        _snackbarEvents.tryEmit("تمت إزالة جهة الاتصال.")
    }

    fun openConversation(conversationId: String) {
        _activeConversationId.value = conversationId
    }

    fun closeConversation() {
        _activeConversationId.value = null
    }

    fun openSavedNotesChat(onReady: (String) -> Unit) {
        val convId = "local_saved_notes"
        val profile = _userProfile.value
        if (_conversations.value.none { it.id == convId }) {
            val now = Timestamp.now()
            val savedConv = Conversation(
                id = convId,
                memberUids = listOf(localUserId),
                member1Uid = localUserId,
                member1PublicId = profile.publicId,
                member1Name = profile.displayName,
                member1Avatar = "",
                member1ColorIndex = profile.avatarColorIndex,
                member2Uid = localUserId,
                member2PublicId = profile.publicId,
                member2Name = profile.displayName,
                member2Avatar = "",
                member2ColorIndex = profile.avatarColorIndex,
                lastMessageLabel = "مساحة مشفّرة لحفظ ملاحظاتك الخاصة",
                lastMessageType = "text",
                lastMessageSenderUid = localUserId,
                lastMessageAt = now,
                createdByUid = localUserId,
                createdAt = now,
                updatedAt = now
            )
            _conversations.value = listOf(savedConv) + _conversations.value
        }
        _activeConversationId.value = convId
        onReady(convId)
    }

    fun startConversationWithPeer(peer: PublicIdEntry, onReady: (String) -> Unit) {
        if (peer.ownerUid == localUserId) {
            openSavedNotesChat(onReady)
            return
        }
        val existing = _conversations.value.firstOrNull { peer.ownerUid in it.memberUids }
        if (existing != null) {
            _activeConversationId.value = existing.id
            onReady(existing.id)
            return
        }
        val convId = "local_conv_${peer.ownerUid}"
        val profile = _userProfile.value
        val now = Timestamp.now()
        val conv = Conversation(
            id = convId,
            memberUids = listOf(localUserId, peer.ownerUid),
            member1Uid = localUserId,
            member1PublicId = profile.publicId,
            member1Name = profile.displayName,
            member1Avatar = "",
            member1ColorIndex = profile.avatarColorIndex,
            member2Uid = peer.ownerUid,
            member2PublicId = peer.publicId,
            member2Name = peer.displayName,
            member2Avatar = "",
            member2ColorIndex = peer.avatarColorIndex,
            lastMessageLabel = "محادثة مشفّرة جديدة",
            lastMessageType = "text",
            lastMessageSenderUid = localUserId,
            lastMessageAt = now,
            createdByUid = localUserId,
            createdAt = now,
            updatedAt = now
        )
        _conversations.value = listOf(conv) + _conversations.value
        _activeConversationId.value = convId
        onReady(convId)
    }

    fun startConversationWithContact(contact: ContactEntry, onReady: (String) -> Unit) {
        val peer = peerDirectory[contact.contactPublicId] ?: PublicIdEntry(
            id = contact.contactPublicId,
            publicId = contact.contactPublicId,
            ownerUid = contact.contactUid,
            displayName = contact.displayName,
            bio = contact.bio,
            avatarColorIndex = contact.avatarColorIndex
        )
        startConversationWithPeer(peer, onReady)
    }

    // -------------------------------------------------------------------------
    // Real Local E2EE Messaging, Edit, Delete, Media & Voice Testing
    // -------------------------------------------------------------------------
    fun sendTextMessage(text: String, replyTo: DecryptedMessage? = null) {
        val conv = activeChatMeta.value.conversation ?: return
        val profile = _userProfile.value
        val peerUid = conv.peerUid(localUserId)
        val peerPubKey = keyDirectory[peerUid]?.publicKeyBase64 ?: return
        if (text.isBlank()) return

        val enc = cryptoManager.encryptMessage(localUserId, peerPubKey, conv.id, text.trim())
        val replyEnc = if (replyTo != null) {
            cryptoManager.encryptMessage(localUserId, peerPubKey, conv.id, replyTo.plaintext.take(80))
        } else null

        val now = Timestamp.now()
        val expMs = if (conv.disappearingSeconds > 0L) System.currentTimeMillis() + conv.disappearingSeconds * 1000L else 0L
        val msg = ChatMessage(
            id = "msg_${UUID.randomUUID().toString().take(12)}",
            conversationId = conv.id,
            memberUids = conv.memberUids,
            senderUid = localUserId,
            senderPublicId = profile.publicId,
            messageType = "text",
            ciphertext = enc.ciphertextBase64,
            iv = enc.ivBase64,
            replyToMessageId = replyTo?.raw?.id.orEmpty(),
            replyPreviewCiphertext = replyEnc?.ciphertextBase64.orEmpty(),
            replyPreviewIv = replyEnc?.ivBase64.orEmpty(),
            deliveryStatus = "read",
            expiresAtEpochMs = expMs,
            createdAt = now,
            updatedAt = now
        )
        appendMessage(conv.id, msg, "رسالة مشفّرة")

        // If chatting with local test peer, send a REAL E2EE-encrypted reply so user can verify full round-trip
        if (peerUid == testPeerUid && !activeChatMeta.value.isPeerBlocked) {
            viewModelScope.launch {
                updatePeerTyping(conv.id, true)
                delay(900L)
                updatePeerTyping(conv.id, false)
                val myPub = keyDirectory[localUserId]!!.publicKeyBase64
                val replyText = "تم استلام رسالتك وفك تشفيرها بنجاح داخل الوضع المحلي! (ECDH-P256 + AES-256-GCM)"
                val peerEnc = cryptoManager.encryptMessage(testPeerUid, myPub, conv.id, replyText)
                val peerMsg = ChatMessage(
                    id = "msg_peer_${UUID.randomUUID().toString().take(10)}",
                    conversationId = conv.id,
                    memberUids = conv.memberUids,
                    senderUid = testPeerUid,
                    senderPublicId = testPeerPublicId,
                    messageType = "text",
                    ciphertext = peerEnc.ciphertextBase64,
                    iv = peerEnc.ivBase64,
                    deliveryStatus = "read",
                    expiresAtEpochMs = expMs,
                    createdAt = Timestamp.now(),
                    updatedAt = Timestamp.now()
                )
                appendMessage(conv.id, peerMsg, "رد مشفّر من الصديق التجريبي")
            }
        }
    }

    fun editMessage(messageId: String, newText: String) {
        val conv = activeChatMeta.value.conversation ?: return
        val peerUid = conv.peerUid(localUserId)
        val peerPubKey = keyDirectory[peerUid]?.publicKeyBase64 ?: return
        val enc = cryptoManager.encryptMessage(localUserId, peerPubKey, conv.id, newText.trim())
        val updatedList = _messagesByConv.value[conv.id].orEmpty().map { m ->
            if (m.id == messageId) {
                m.copy(ciphertext = enc.ciphertextBase64, iv = enc.ivBase64, edited = true, updatedAt = Timestamp.now())
            } else m
        }
        _messagesByConv.value = _messagesByConv.value + (conv.id to updatedList)
        _snackbarEvents.tryEmit("تم تعديل الرسالة المشفّرة.")
    }

    fun deleteMessage(messageId: String) {
        val conv = activeChatMeta.value.conversation ?: return
        val updatedList = _messagesByConv.value[conv.id].orEmpty().map { m ->
            if (m.id == messageId) {
                m.copy(deleted = true, ciphertext = "", iv = "", updatedAt = Timestamp.now())
            } else m
        }
        _messagesByConv.value = _messagesByConv.value + (conv.id to updatedList)
        _snackbarEvents.tryEmit("تم حذف الرسالة.")
    }

    fun markIncomingMessagesAsRead(messages: List<DecryptedMessage>) {
        val convId = _activeConversationId.value ?: return
        val ids = messages
            .filter { it.raw.senderUid != localUserId && it.raw.deliveryStatus != "read" }
            .map { it.raw.id }
            .toSet()
        if (ids.isEmpty()) return
        val updated = _messagesByConv.value[convId].orEmpty().map { m ->
            if (m.id in ids) m.copy(deliveryStatus = "read") else m
        }
        _messagesByConv.value = _messagesByConv.value + (convId to updated)
    }

    fun updateTypingState(typing: Boolean) {
        val convId = _activeConversationId.value ?: return
        _conversations.value = _conversations.value.map { c ->
            if (c.id == convId) {
                c.copy(typingUid = if (typing) localUserId else "")
            } else c
        }
    }

    fun sendSelectedMediaUri(uri: Uri, messageType: String, fallbackMime: String) {
        val conv = activeChatMeta.value.conversation ?: return
        val profile = _userProfile.value
        viewModelScope.launch {
            _isUploadingMedia.value = true
            try {
                val fileName = resolveDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
                val mime = appContext.contentResolver.getType(uri) ?: fallbackMime
                val cacheFile = File(appContext.cacheDir, "local_media_${UUID.randomUUID().toString().take(8)}_$fileName")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                val localUrl = "local://${cacheFile.name}"
                localMediaFiles[localUrl] = cacheFile
                val now = Timestamp.now()
                val msg = ChatMessage(
                    id = "msg_media_${UUID.randomUUID().toString().take(10)}",
                    conversationId = conv.id,
                    memberUids = conv.memberUids,
                    senderUid = localUserId,
                    senderPublicId = profile.publicId,
                    messageType = messageType,
                    mediaUrl = localUrl,
                    mediaFileName = fileName,
                    mediaMimeType = mime,
                    mediaSizeBytes = cacheFile.length(),
                    deliveryStatus = "read",
                    createdAt = now,
                    updatedAt = now
                )
                appendMessage(conv.id, msg, fileName)
                _snackbarEvents.tryEmit("تم إرفاق الملف محليًا للاختبار (وللرفع على Hostinger سجّل عبر Google).")
            } catch (e: Exception) {
                _snackbarEvents.tryEmit(e.localizedMessage ?: "تعذر قراءة الملف المختار.")
            } finally {
                _isUploadingMedia.value = false
            }
        }
    }

    fun sendRecordedVoiceFile(file: File, durationSec: Long) {
        val conv = activeChatMeta.value.conversation ?: return
        val profile = _userProfile.value
        val localUrl = "local://${file.name}"
        localMediaFiles[localUrl] = file
        val now = Timestamp.now()
        val msg = ChatMessage(
            id = "msg_voice_${UUID.randomUUID().toString().take(10)}",
            conversationId = conv.id,
            memberUids = conv.memberUids,
            senderUid = localUserId,
            senderPublicId = profile.publicId,
            messageType = "voice",
            mediaUrl = localUrl,
            mediaFileName = file.name,
            mediaMimeType = "audio/mp4",
            mediaSizeBytes = file.length(),
            mediaDurationSec = durationSec,
            deliveryStatus = "read",
            createdAt = now,
            updatedAt = now
        )
        appendMessage(conv.id, msg, "رسالة صوتية (${durationSec}ث)")
        _snackbarEvents.tryEmit("تم إرسال الرسالة الصوتية محليًا.")
    }

    suspend fun downloadAuthorizedMedia(downloadUrl: String, fileName: String): Result<File> {
        val local = localMediaFiles[downloadUrl]
        return if (local != null && local.exists()) {
            Result.success(local)
        } else {
            Result.failure(IllegalStateException("الملف متاح فقط في جلسة الاختبار الحالية."))
        }
    }

    fun toggleArchiveConversation(conversation: Conversation) {
        _conversations.value = _conversations.value.map { c ->
            if (c.id == conversation.id) {
                val updated = if (localUserId in c.archivedByUids) {
                    c.archivedByUids - localUserId
                } else {
                    c.archivedByUids + localUserId
                }
                c.copy(archivedByUids = updated)
            } else c
        }
    }

    fun deleteConversation(conversation: Conversation, onDeleted: () -> Unit = {}) {
        _conversations.value = _conversations.value.filterNot { it.id == conversation.id }
        _snackbarEvents.tryEmit("تم حذف المحادثة.")
        onDeleted()
    }

    fun setConversationDisappearingTimer(conversationId: String, seconds: Long) {
        _conversations.value = _conversations.value.map { c ->
            if (c.id == conversationId) c.copy(disappearingSeconds = seconds) else c
        }
        _snackbarEvents.tryEmit("تم تحديث مؤقت الرسائل ذاتية الاختفاء.")
    }

    fun blockUser(uid: String, publicId: String, displayName: String) {
        if (uid == localUserId) return
        if (_blockedUsers.value.none { it.blockedUid == uid }) {
            _blockedUsers.value = _blockedUsers.value + BlockedUserEntry(
                id = uid,
                userId = localUserId,
                blockedUid = uid,
                blockedPublicId = publicId,
                blockedDisplayName = displayName,
                createdAt = Timestamp.now()
            )
        }
        _snackbarEvents.tryEmit("تم حظر $displayName.")
    }

    fun unblockUser(uid: String) {
        _blockedUsers.value = _blockedUsers.value.filterNot { it.blockedUid == uid }
        _snackbarEvents.tryEmit("تم إلغاء الحظر.")
    }

    fun reportUser(
        reportedUid: String,
        reportedPublicId: String,
        conversationId: String,
        reason: String,
        details: String
    ) {
        _snackbarEvents.tryEmit("تم تسجيل البلاغ في وضع الاختبار المحلي.")
    }

    fun markNotificationRead(notificationId: String) {
        _notifications.value = _notifications.value.map { n ->
            if (n.id == notificationId) n.copy(read = true) else n
        }
    }

    private fun appendMessage(convId: String, msg: ChatMessage, preview: String) {
        val current = _messagesByConv.value[convId].orEmpty()
        _messagesByConv.value = _messagesByConv.value + (convId to (current + msg))
        _conversations.value = _conversations.value.map { c ->
            if (c.id == convId) {
                c.copy(
                    lastMessageLabel = preview,
                    lastMessageType = msg.messageType,
                    lastMessageSenderUid = msg.senderUid,
                    lastMessageAt = msg.createdAt,
                    updatedAt = msg.createdAt
                )
            } else c
        }
    }

    private fun updatePeerTyping(convId: String, typing: Boolean) {
        _conversations.value = _conversations.value.map { c ->
            if (c.id == convId) {
                c.copy(typingUid = if (typing) testPeerUid else "")
            } else c
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun decryptSingleMessage(msg: ChatMessage, peerPublicKeyBase64: String?): DecryptedMessage {
        if (msg.deleted) return DecryptedMessage(msg, "تم حذف هذه الرسالة", "", true)
        if (msg.messageType != "text") {
            val label = msg.mediaFileName.ifBlank { msg.messageType }
            return DecryptedMessage(msg, label, "", true)
        }
        if (peerPublicKeyBase64.isNullOrBlank() || msg.ciphertext.isBlank() || msg.iv.isBlank()) {
            return DecryptedMessage(msg, "رسالة مشفّرة", "", false)
        }
        val dec = cryptoManager.decryptMessage(localUserId, peerPublicKeyBase64, msg.conversationId, msg.ciphertext, msg.iv)
        val replyPlain = if (msg.replyPreviewCiphertext.isNotBlank() && msg.replyPreviewIv.isNotBlank()) {
            cryptoManager.decryptMessage(localUserId, peerPublicKeyBase64, msg.conversationId, msg.replyPreviewCiphertext, msg.replyPreviewIv)
                .getOrDefault("رسالة مشفّرة")
        } else ""
        return if (dec.isSuccess) {
            DecryptedMessage(msg, dec.getOrThrow(), replyPlain, true)
        } else {
            DecryptedMessage(msg, "تعذر فك التشفير", replyPlain, false)
        }
    }
}
