package com.example.data.firestore

import android.content.Context
import android.os.Build
import com.example.R
import com.example.data.crypto.E2eeCryptoManager
import com.example.data.crypto.EncryptedPayload
import com.example.data.model.BlockedUserEntry
import com.example.data.model.ChatMessage
import com.example.data.model.ContactEntry
import com.example.data.model.Conversation
import com.example.data.model.DeviceSession
import com.example.data.model.EncryptionKeyRecord
import com.example.data.model.PublicIdEntry
import com.example.data.model.RecoveryDataRecord
import com.example.data.model.UserNotificationEntry
import com.example.data.model.UserProfile
import com.example.data.remote.HostingerUploadResult
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID

class KatemRepository(
    private val db: FirebaseFirestore,
    private val cryptoManager: E2eeCryptoManager,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        cryptoManager = E2eeCryptoManager(context.applicationContext)
    )

    private val secureRandom = SecureRandom()
    private val recentSendTimestamps = ArrayDeque<Long>()

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("يجب تسجيل الدخول أولًا قبل الوصول إلى بيانات المحادثات.")

    // -------------------------------------------------------------------------
    // 1. Account Provisioning (Unique Public User ID + E2EE Key + Device Sync)
    // -------------------------------------------------------------------------
    suspend fun ensureUserInitialized(deviceId: String): Result<UserProfile> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val userDocRef = db.collection("users").document(uid)
        return try {
            val existingSnap = userDocRef.get().await()
            val profile = if (existingSnap.exists()) {
                val parsed = existingSnap.toObject(
                    UserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: error("تعذر قراءة ملف المستخدم.")
                syncPresenceInternal(uid, parsed.publicId, online = true, profile = parsed)
                parsed
            } else {
                createNewAccountWithUniquePublicId(uid)
            }

            // Ensure E2EE public key exists and matches local keypair
            publishPublicEncryptionKey(uid)
            // Register current device session
            registerDeviceSession(uid, deviceId)
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userDocRef.path)
            Result.failure(e)
        }
    }

    private suspend fun createNewAccountWithUniquePublicId(uid: String): UserProfile {
        val defaultName = "مستخدم كاتم"
        val colorIdx = secureRandom.nextInt(8).toLong()

        // Atomically claim a collision-free Public User ID (KTM-XXXXXX) via transaction
        var claimedPublicId: String? = null
        repeat(8) {
            if (claimedPublicId != null) return@repeat
            val candidate = generateCandidatePublicId()
            val publicIdRef = db.collection("publicIds").document(candidate)
            val userRef = db.collection("users").document(uid)
            val succeeded = try {
                db.runTransaction { tx ->
                    if (tx.get(publicIdRef).exists()) {
                        return@runTransaction false
                    }
                    val now = FieldValue.serverTimestamp()
                    tx.set(
                        publicIdRef,
                        mapOf(
                            "publicId" to candidate,
                            "ownerUid" to uid,
                            "displayName" to defaultName,
                            "bio" to "",
                            "avatarUrl" to "",
                            "avatarColorIndex" to colorIdx,
                            "onlineVisible" to true,
                            "online" to true,
                            "lastSeenVisible" to true,
                            "lastSeenAt" to now,
                            "createdAt" to now,
                            "updatedAt" to now
                        )
                    )
                    tx.set(
                        userRef,
                        mapOf(
                            "userId" to uid,
                            "publicId" to candidate,
                            "displayName" to defaultName,
                            "bio" to "",
                            "avatarUrl" to "",
                            "avatarColorIndex" to colorIdx,
                            "hideLastSeen" to false,
                            "showOnlineStatus" to true,
                            "hideNotificationPreview" to true,
                            "screenshotProtection" to false,
                            "blurOnBackground" to true,
                            "disappearingDefaultSec" to 0L,
                            "lastSeenAt" to now,
                            "createdAt" to now,
                            "updatedAt" to now
                        )
                    )
                    true
                }.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (succeeded) {
                claimedPublicId = candidate
            }
        }

        val finalPublicId = claimedPublicId
            ?: throw IllegalStateException("تعذر توليد معرّف عام فريد، يرجى المحاولة مرة أخرى.")

        val createdSnap = db.collection("users").document(uid).get().await()
        return createdSnap.toObject(
            UserProfile::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
        ) ?: UserProfile(
            id = uid,
            userId = uid,
            publicId = finalPublicId,
            displayName = defaultName,
            avatarColorIndex = colorIdx
        )
    }

    private fun generateCandidatePublicId(): String {
        val digits = (0 until 6).map { secureRandom.nextInt(10) }.joinToString("")
        val suffix = "ABCDEFGHJKLMNPQRSTUVWXYZ"[secureRandom.nextInt(24)]
        return "KTM-$digits$suffix"
    }

    private suspend fun publishPublicEncryptionKey(uid: String) {
        val keyRef = db.collection("encryptionKeys").document(uid)
        val bundle = cryptoManager.getPublicKeyBundle(uid)
        val existing = keyRef.get().await()
        val now = FieldValue.serverTimestamp()
        if (!existing.exists()) {
            keyRef.set(
                mapOf(
                    "userId" to uid,
                    "publicKeyBase64" to bundle.publicKeyBase64,
                    "fingerprint" to bundle.fingerprint,
                    "algorithm" to bundle.algorithm,
                    "keyVersion" to 1L,
                    "createdAt" to now,
                    "updatedAt" to now
                )
            ).await()
        } else {
            val currentPub = existing.getString("publicKeyBase64").orEmpty()
            if (currentPub != bundle.publicKeyBase64) {
                val nextVersion = (existing.getLong("keyVersion") ?: 1L) + 1L
                keyRef.update(
                    mapOf(
                        "publicKeyBase64" to bundle.publicKeyBase64,
                        "fingerprint" to bundle.fingerprint,
                        "algorithm" to bundle.algorithm,
                        "keyVersion" to nextVersion,
                        "updatedAt" to now
                    )
                ).await()
            }
        }
    }

    private suspend fun registerDeviceSession(uid: String, deviceId: String) {
        val ref = db.collection("users").document(uid).collection("devices").document(deviceId)
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(75)
        val now = FieldValue.serverTimestamp()
        val existing = ref.get().await()
        if (!existing.exists()) {
            ref.set(
                mapOf(
                    "userId" to uid,
                    "deviceName" to deviceName.ifBlank { "Android Device" },
                    "platform" to "android",
                    "appVersion" to "1.0",
                    "lastActiveAt" to now,
                    "createdAt" to now,
                    "updatedAt" to now
                )
            ).await()
        } else {
            ref.update(
                mapOf(
                    "deviceName" to deviceName.ifBlank { "Android Device" },
                    "appVersion" to "1.0",
                    "lastActiveAt" to now,
                    "updatedAt" to now
                )
            ).await()
        }
    }

    // -------------------------------------------------------------------------
    // 2. Real-Time Observers (Scoped Strictly to Authenticated UID)
    // -------------------------------------------------------------------------
    fun observeUserProfile(uid: String): Flow<UserProfile?> = flow {
        val path = "users/$uid"
        emitAll(
            db.collection("users").document(uid)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.GET, path, uid)
                    throw error
                }
        )
    }

    fun observeConversations(uid: String): Flow<List<Conversation>> = flow {
        val path = "conversations"
        emitAll(
            db.collection("conversations")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(Conversation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filterNot { it.isDeletedFor(uid) }
                        .sortedByDescending { (it.lastMessageAt ?: it.createdAt)?.toDate()?.time ?: 0L }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeMessages(uid: String, conversationId: String): Flow<List<ChatMessage>> = flow {
        val path = "conversations/$conversationId/messages"
        emitAll(
            db.collection("conversations").document(conversationId)
                .collection("messages")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(ChatMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeConversation(uid: String, conversationId: String): Flow<Conversation?> = flow {
        val path = "conversations/$conversationId"
        emitAll(
            db.collection("conversations").document(conversationId)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(Conversation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.GET, path, uid)
                    throw error
                }
        )
    }

    fun observeContacts(uid: String): Flow<List<ContactEntry>> = flow {
        val path = "users/$uid/contacts"
        emitAll(
            db.collection("users").document(uid).collection("contacts")
                .snapshots()
                .map { snap ->
                    snap.toObjects(ContactEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.displayName.lowercase() }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeBlockedUsers(uid: String): Flow<List<BlockedUserEntry>> = flow {
        val path = "users/$uid/blockedUsers"
        emitAll(
            db.collection("users").document(uid).collection("blockedUsers")
                .snapshots()
                .map { snap ->
                    snap.toObjects(BlockedUserEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeDevices(uid: String): Flow<List<DeviceSession>> = flow {
        val path = "users/$uid/devices"
        emitAll(
            db.collection("users").document(uid).collection("devices")
                .snapshots()
                .map { snap ->
                    snap.toObjects(DeviceSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.lastActiveAt?.toDate()?.time ?: 0L }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeNotifications(uid: String): Flow<List<UserNotificationEntry>> = flow {
        val path = "users/$uid/notifications"
        emitAll(
            db.collection("users").document(uid).collection("notifications")
                .snapshots()
                .map { snap ->
                    snap.toObjects(UserNotificationEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeRecoveryData(uid: String): Flow<RecoveryDataRecord?> = flow {
        val path = "users/$uid/recoveryData/primary"
        emitAll(
            db.collection("users").document(uid).collection("recoveryData").document("primary")
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(RecoveryDataRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.GET, path, uid)
                    throw error
                }
        )
    }

    // -------------------------------------------------------------------------
    // 3. Profile, Presence & Privacy Updates
    // -------------------------------------------------------------------------
    suspend fun updateProfile(
        publicId: String,
        displayName: String,
        bio: String,
        avatarUrl: String,
        avatarColorIndex: Long
    ): Result<Unit> {
        val trimmedName = displayName.trim().take(60)
        val trimmedBio = bio.trim().take(200)
        val trimmedAvatar = avatarUrl.trim().take(600)
        if (trimmedName.isEmpty()) {
            return Result.failure(IllegalArgumentException("الاسم الظاهر مطلوب ولا يمكن أن يكون فارغًا."))
        }
        var path = "users"
        return try {
            val uid = requireUserId()
            path = "users/$uid"
            val now = FieldValue.serverTimestamp()
            val batch = db.batch()
            batch.update(
                db.collection("users").document(uid),
                mapOf(
                    "displayName" to trimmedName,
                    "bio" to trimmedBio,
                    "avatarUrl" to trimmedAvatar,
                    "avatarColorIndex" to avatarColorIndex.coerceIn(0L, 7L),
                    "updatedAt" to now
                )
            )
            if (publicId.isNotBlank()) {
                batch.update(
                    db.collection("publicIds").document(publicId),
                    mapOf(
                        "displayName" to trimmedName,
                        "bio" to trimmedBio,
                        "avatarUrl" to trimmedAvatar,
                        "avatarColorIndex" to avatarColorIndex.coerceIn(0L, 7L),
                        "updatedAt" to now
                    )
                )
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updatePrivacySettings(
        publicId: String,
        hideLastSeen: Boolean,
        showOnlineStatus: Boolean,
        hideNotificationPreview: Boolean,
        screenshotProtection: Boolean,
        blurOnBackground: Boolean,
        disappearingDefaultSec: Long
    ): Result<Unit> {
        var path = "users"
        return try {
            val uid = requireUserId()
            path = "users/$uid"
            val now = FieldValue.serverTimestamp()
            val clampedDisappearing = disappearingDefaultSec.coerceIn(0L, 2_592_000L)
            val batch = db.batch()
            batch.update(
                db.collection("users").document(uid),
                mapOf(
                    "hideLastSeen" to hideLastSeen,
                    "showOnlineStatus" to showOnlineStatus,
                    "hideNotificationPreview" to hideNotificationPreview,
                    "screenshotProtection" to screenshotProtection,
                    "blurOnBackground" to blurOnBackground,
                    "disappearingDefaultSec" to clampedDisappearing,
                    "updatedAt" to now
                )
            )
            if (publicId.isNotBlank()) {
                batch.update(
                    db.collection("publicIds").document(publicId),
                    mapOf(
                        "onlineVisible" to showOnlineStatus,
                        "online" to showOnlineStatus,
                        "lastSeenVisible" to !hideLastSeen,
                        "lastSeenAt" to now,
                        "updatedAt" to now
                    )
                )
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun syncOnlinePresence(publicId: String, online: Boolean, profile: UserProfile) {
        val uid = auth.currentUser?.uid ?: return
        runCatching { syncPresenceInternal(uid, publicId, online, profile) }
    }

    private suspend fun syncPresenceInternal(uid: String, publicId: String, online: Boolean, profile: UserProfile) {
        if (publicId.isBlank()) return
        val now = FieldValue.serverTimestamp()
        db.collection("publicIds").document(publicId).update(
            mapOf(
                "onlineVisible" to profile.showOnlineStatus,
                "online" to (online && profile.showOnlineStatus),
                "lastSeenVisible" to !profile.hideLastSeen,
                "lastSeenAt" to now,
                "updatedAt" to now
            )
        ).await()
    }

    // -------------------------------------------------------------------------
    // 4. Friend Discovery via Public ID, QR Code, or Invite Link
    // -------------------------------------------------------------------------
    fun normalizePublicIdInput(rawInput: String): String {
        val cleaned = rawInput.trim()
        val extracted = when {
            cleaned.startsWith("katem://add/", ignoreCase = true) ->
                cleaned.substringAfter("katem://add/").substringBefore("?").substringBefore("/")
            cleaned.contains("/add/", ignoreCase = true) ->
                cleaned.substringAfter("/add/").substringBefore("?").substringBefore("/")
            else -> cleaned
        }
        return extracted.trim().uppercase().replace(" ", "")
    }

    suspend fun findUserByPublicId(rawInput: String): Result<PublicIdEntry?> {
        val normalized = normalizePublicIdInput(rawInput)
        if (!Regex("^[A-Z0-9\\-]{6,24}$").matches(normalized)) {
            return Result.failure(IllegalArgumentException("صيغة المعرّف غير صالحة. مثال صحيح: KTM-482917A"))
        }
        val path = "publicIds/$normalized"
        return try {
            requireUserId()
            val snap = db.collection("publicIds").document(normalized).get().await()
            if (!snap.exists()) {
                Result.success(null)
            } else {
                Result.success(
                    snap.toObject(PublicIdEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun getPeerPublicKey(peerUid: String): Result<EncryptionKeyRecord?> {
        val path = "encryptionKeys/$peerUid"
        return try {
            requireUserId()
            val snap = db.collection("encryptionKeys").document(peerUid).get().await()
            Result.success(
                if (snap.exists()) {
                    snap.toObject(EncryptionKeyRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else null
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun saveContact(peer: PublicIdEntry): Result<Unit> {
        var path = "contacts"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("contacts").document(peer.ownerUid)
            path = docRef.path
            val now = FieldValue.serverTimestamp()
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "displayName" to peer.displayName.take(60).ifBlank { peer.publicId },
                        "bio" to peer.bio.take(200),
                        "avatarUrl" to peer.avatarUrl.take(600),
                        "avatarColorIndex" to peer.avatarColorIndex.coerceIn(0L, 7L),
                        "updatedAt" to now
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "contactUid" to peer.ownerUid,
                        "contactPublicId" to peer.publicId,
                        "displayName" to peer.displayName.take(60).ifBlank { peer.publicId },
                        "bio" to peer.bio.take(200),
                        "avatarUrl" to peer.avatarUrl.take(600),
                        "avatarColorIndex" to peer.avatarColorIndex.coerceIn(0L, 7L),
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun removeContact(contactUid: String): Result<Unit> {
        var path = "contacts"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("contacts").document(contactUid)
            path = ref.path
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // 5. Conversations & Real-Time E2EE Messaging
    // -------------------------------------------------------------------------
    fun deterministicConversationId(uidA: String, uidB: String): String {
        return if (uidA == uidB) {
            "self_$uidA"
        } else {
            val sorted = listOf(uidA, uidB).sorted()
            "${sorted[0]}_${sorted[1]}"
        }
    }

    suspend fun getOrCreateConversation(myProfile: UserProfile, peer: PublicIdEntry): Result<String> {
        var path = "conversations"
        return try {
            val uid = requireUserId()
            val convId = deterministicConversationId(uid, peer.ownerUid)
            val convRef = db.collection("conversations").document(convId)
            path = convRef.path
            val existing = convRef.get().await()
            if (existing.exists()) {
                // Restore conversation if previously soft-deleted by current user
                val deletedBy = (existing.get("deletedByUids") as? List<*>)
                    ?.filterIsInstance<String>()
                    .orEmpty()
                if (uid in deletedBy) {
                    convRef.update(
                        mapOf(
                            "deletedByUids" to deletedBy.filterNot { it == uid },
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
                return Result.success(convId)
            }

            val isSelf = uid == peer.ownerUid
            val memberUids = if (isSelf) listOf(uid) else listOf(uid, peer.ownerUid).sorted()
            val now = FieldValue.serverTimestamp()
            val payload = mapOf(
                "memberUids" to memberUids,
                "member1Uid" to uid,
                "member1PublicId" to myProfile.publicId,
                "member1Name" to myProfile.displayName.take(60).ifBlank { "مستخدم كاتم" },
                "member1Avatar" to myProfile.avatarUrl.take(600),
                "member1ColorIndex" to myProfile.avatarColorIndex.coerceIn(0L, 7L),
                "member2Uid" to peer.ownerUid,
                "member2PublicId" to peer.publicId,
                "member2Name" to peer.displayName.take(60).ifBlank { peer.publicId },
                "member2Avatar" to peer.avatarUrl.take(600),
                "member2ColorIndex" to peer.avatarColorIndex.coerceIn(0L, 7L),
                "lastMessageLabel" to "بدأت محادثة مشفّرة",
                "lastMessageType" to "system",
                "lastMessageSenderUid" to uid,
                "lastMessageAt" to now,
                "typingUid" to "",
                "archivedByUids" to emptyList<String>(),
                "deletedByUids" to emptyList<String>(),
                "disappearingSeconds" to myProfile.disappearingDefaultSec.coerceIn(0L, 2_592_000L),
                "createdByUid" to uid,
                "createdAt" to now,
                "updatedAt" to now
            )
            convRef.set(payload).await()
            Result.success(convId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun sendEncryptedTextMessage(
        conversation: Conversation,
        myProfile: UserProfile,
        peerPublicKeyBase64: String,
        plaintext: String,
        replyToMessageId: String = "",
        replyPreviewPlaintext: String = ""
    ): Result<Unit> {
        val trimmed = plaintext.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("الرسالة فارغة."))
        if (!checkClientRateLimit()) {
            return Result.failure(IllegalStateException("تم تجاوز حد الإرسال السريع مؤقتًا. انتظر بضع ثوانٍ."))
        }
        var path = "conversations/${conversation.id}/messages"
        return try {
            val uid = requireUserId()
            val encrypted: EncryptedPayload = cryptoManager.encryptMessage(
                myUid = uid,
                peerPublicKeyBase64 = peerPublicKeyBase64,
                conversationId = conversation.id,
                plaintext = trimmed
            )
            val encryptedReply: EncryptedPayload? = if (replyToMessageId.isNotBlank() && replyPreviewPlaintext.isNotBlank()) {
                cryptoManager.encryptMessage(
                    myUid = uid,
                    peerPublicKeyBase64 = peerPublicKeyBase64,
                    conversationId = conversation.id,
                    plaintext = replyPreviewPlaintext.take(120)
                )
            } else null

            val msgRef = db.collection("conversations").document(conversation.id)
                .collection("messages").document()
            path = msgRef.path
            val now = FieldValue.serverTimestamp()
            val expiresAtMs = if (conversation.disappearingSeconds > 0L) {
                System.currentTimeMillis() + conversation.disappearingSeconds * 1000L
            } else 0L

            val batch = db.batch()
            batch.set(
                msgRef,
                mapOf(
                    "conversationId" to conversation.id,
                    "memberUids" to conversation.memberUids,
                    "senderUid" to uid,
                    "senderPublicId" to myProfile.publicId,
                    "messageType" to "text",
                    "ciphertext" to encrypted.ciphertextBase64,
                    "iv" to encrypted.ivBase64,
                    "encryptionScheme" to E2eeCryptoManager.ALGORITHM_SUITE,
                    "mediaFileId" to "",
                    "mediaUrl" to "",
                    "mediaMimeType" to "",
                    "mediaFileName" to "",
                    "mediaSizeBytes" to 0L,
                    "mediaDurationSec" to 0L,
                    "replyToMessageId" to replyToMessageId,
                    "replyPreviewCiphertext" to (encryptedReply?.ciphertextBase64 ?: ""),
                    "replyPreviewIv" to (encryptedReply?.ivBase64 ?: ""),
                    "deliveryStatus" to "sent",
                    "edited" to false,
                    "deleted" to false,
                    "expiresAtEpochMs" to expiresAtMs,
                    "createdAt" to now,
                    "updatedAt" to now
                )
            )
            batch.update(
                db.collection("conversations").document(conversation.id),
                mapOf(
                    "lastMessageLabel" to "رسالة مشفّرة",
                    "lastMessageType" to "text",
                    "lastMessageSenderUid" to uid,
                    "lastMessageAt" to now,
                    "deletedByUids" to emptyList<String>(),
                    "typingUid" to "",
                    "updatedAt" to now
                )
            )
            batch.commit().await()

            // Deliver privacy-safe notification record to peer (if not self-chat)
            val peerUid = conversation.peerUid(uid)
            if (peerUid != uid) {
                createPrivacyNotificationSafe(
                    recipientUid = peerUid,
                    conversationId = conversation.id,
                    senderUid = uid,
                    senderPublicId = myProfile.publicId,
                    senderName = myProfile.displayName.ifBlank { myProfile.publicId },
                    type = "message",
                    previewHidden = myProfile.hideNotificationPreview
                )
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun sendHostingerMediaMessage(
        conversation: Conversation,
        myProfile: UserProfile,
        messageType: String,
        upload: HostingerUploadResult,
        durationSec: Long = 0L
    ): Result<Unit> {
        var path = "conversations/${conversation.id}/messages"
        return try {
            val uid = requireUserId()
            val now = FieldValue.serverTimestamp()
            val mediaRef = db.collection("mediaFiles").document(upload.mediaId)
            val msgRef = db.collection("conversations").document(conversation.id)
                .collection("messages").document()
            path = msgRef.path
            val expiresAtMs = if (conversation.disappearingSeconds > 0L) {
                System.currentTimeMillis() + conversation.disappearingSeconds * 1000L
            } else 0L

            val label = when (messageType) {
                "image" -> "صورة"
                "video" -> "فيديو"
                "voice" -> "رسالة صوتية"
                else -> "مستند"
            }

            val batch = db.batch()
            batch.set(
                mediaRef,
                mapOf(
                    "conversationId" to conversation.id,
                    "memberUids" to conversation.memberUids,
                    "uploaderUid" to uid,
                    "storageProvider" to "hostinger",
                    "remotePath" to upload.remotePath.take(400),
                    "downloadUrl" to upload.downloadUrl.take(1024),
                    "mimeType" to upload.mimeType.take(128),
                    "fileName" to upload.fileName.take(200),
                    "sizeBytes" to upload.sizeBytes.coerceAtLeast(0L),
                    "durationSec" to durationSec.coerceAtLeast(0L),
                    "encrypted" to false,
                    "createdAt" to now
                )
            )
            batch.set(
                msgRef,
                mapOf(
                    "conversationId" to conversation.id,
                    "memberUids" to conversation.memberUids,
                    "senderUid" to uid,
                    "senderPublicId" to myProfile.publicId,
                    "messageType" to messageType,
                    "ciphertext" to "",
                    "iv" to "",
                    "encryptionScheme" to "NONE_MEDIA",
                    "mediaFileId" to upload.mediaId,
                    "mediaUrl" to upload.downloadUrl.take(1024),
                    "mediaMimeType" to upload.mimeType.take(128),
                    "mediaFileName" to upload.fileName.take(200),
                    "mediaSizeBytes" to upload.sizeBytes.coerceAtLeast(0L),
                    "mediaDurationSec" to durationSec.coerceAtLeast(0L),
                    "replyToMessageId" to "",
                    "replyPreviewCiphertext" to "",
                    "replyPreviewIv" to "",
                    "deliveryStatus" to "sent",
                    "edited" to false,
                    "deleted" to false,
                    "expiresAtEpochMs" to expiresAtMs,
                    "createdAt" to now,
                    "updatedAt" to now
                )
            )
            batch.update(
                db.collection("conversations").document(conversation.id),
                mapOf(
                    "lastMessageLabel" to label,
                    "lastMessageType" to messageType,
                    "lastMessageSenderUid" to uid,
                    "lastMessageAt" to now,
                    "deletedByUids" to emptyList<String>(),
                    "typingUid" to "",
                    "updatedAt" to now
                )
            )
            batch.commit().await()

            val peerUid = conversation.peerUid(uid)
            if (peerUid != uid) {
                createPrivacyNotificationSafe(
                    recipientUid = peerUid,
                    conversationId = conversation.id,
                    senderUid = uid,
                    senderPublicId = myProfile.publicId,
                    senderName = myProfile.displayName.ifBlank { myProfile.publicId },
                    type = "media",
                    previewHidden = myProfile.hideNotificationPreview
                )
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun editEncryptedMessage(
        conversationId: String,
        messageId: String,
        peerPublicKeyBase64: String,
        newPlaintext: String
    ): Result<Unit> {
        val trimmed = newPlaintext.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("النص الجديد فارغ."))
        val path = "conversations/$conversationId/messages/$messageId"
        return try {
            val uid = requireUserId()
            val encrypted = cryptoManager.encryptMessage(uid, peerPublicKeyBase64, conversationId, trimmed)
            db.document(path).update(
                mapOf(
                    "ciphertext" to encrypted.ciphertextBase64,
                    "iv" to encrypted.ivBase64,
                    "edited" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteMessage(conversationId: String, messageId: String): Result<Unit> {
        val path = "conversations/$conversationId/messages/$messageId"
        return try {
            requireUserId()
            db.document(path).update(
                mapOf(
                    "ciphertext" to "",
                    "iv" to "",
                    "mediaUrl" to "",
                    "mediaFileName" to "",
                    "deleted" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun markMessagesRead(conversationId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        runCatching {
            requireUserId()
            val batch = db.batch()
            val now = FieldValue.serverTimestamp()
            messageIds.take(25).forEach { msgId ->
                val ref = db.collection("conversations").document(conversationId)
                    .collection("messages").document(msgId)
                batch.update(ref, mapOf("deliveryStatus" to "read", "updatedAt" to now))
            }
            batch.commit().await()
        }
    }

    suspend fun setTypingIndicator(conversationId: String, isTyping: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            db.collection("conversations").document(conversationId).update(
                mapOf(
                    "typingUid" to if (isTyping) uid else "",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    suspend fun toggleArchiveConversation(conversation: Conversation): Result<Unit> {
        val path = "conversations/${conversation.id}"
        return try {
            val uid = requireUserId()
            val updated = if (uid in conversation.archivedByUids) {
                conversation.archivedByUids.filterNot { it == uid }
            } else {
                (conversation.archivedByUids + uid).distinct().take(2)
            }
            db.document(path).update(
                mapOf(
                    "archivedByUids" to updated,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteConversationForMe(conversation: Conversation): Result<Unit> {
        val path = "conversations/${conversation.id}"
        return try {
            val uid = requireUserId()
            val updated = (conversation.deletedByUids + uid).distinct().take(2)
            db.document(path).update(
                mapOf(
                    "deletedByUids" to updated,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateConversationDisappearingTimer(conversationId: String, seconds: Long): Result<Unit> {
        val path = "conversations/$conversationId"
        return try {
            requireUserId()
            db.document(path).update(
                mapOf(
                    "disappearingSeconds" to seconds.coerceIn(0L, 2_592_000L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // 6. Account Recovery Vault (PBKDF2 + AES-GCM + Brute-Force Cooldown)
    // -------------------------------------------------------------------------
    suspend fun saveRecoveryBackup(passphrase: String): Result<Unit> {
        var path = "recoveryData"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("recoveryData").document("primary")
            path = ref.path
            val blob = cryptoManager.createRecoveryVaultBlob(uid, passphrase)
            val now = FieldValue.serverTimestamp()
            val existing = ref.get().await()
            if (existing.exists()) {
                ref.update(
                    mapOf(
                        "encryptedBlob" to blob.encryptedBlobBase64,
                        "saltBase64" to blob.saltBase64,
                        "ivBase64" to blob.ivBase64,
                        "kdfIterations" to blob.kdfIterations.toLong(),
                        "keyVersion" to 1L,
                        "failedAttempts" to 0L,
                        "lockedUntilEpochMs" to 0L,
                        "updatedAt" to now
                    )
                ).await()
            } else {
                ref.set(
                    mapOf(
                        "userId" to uid,
                        "encryptedBlob" to blob.encryptedBlobBase64,
                        "saltBase64" to blob.saltBase64,
                        "ivBase64" to blob.ivBase64,
                        "kdfIterations" to blob.kdfIterations.toLong(),
                        "keyVersion" to 1L,
                        "failedAttempts" to 0L,
                        "lockedUntilEpochMs" to 0L,
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun verifyAndRestoreRecoveryBackup(passphrase: String): Result<String> {
        var path = "recoveryData"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("recoveryData").document("primary")
            path = ref.path
            val snap = ref.get().await()
            if (!snap.exists()) {
                return Result.failure(IllegalStateException("لا توجد نسخة احتياطية للاستعادة بعد."))
            }
            val record = snap.toObject(RecoveryDataRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(IllegalStateException("بيانات الاستعادة غير صالحة."))

            val nowMs = System.currentTimeMillis()
            if (record.lockedUntilEpochMs > nowMs) {
                val waitSec = ((record.lockedUntilEpochMs - nowMs) / 1000L).coerceAtLeast(1L)
                return Result.failure(IllegalStateException("تم إيقاف محاولات الاستعادة مؤقتًا لحماية الحساب. انتظر $waitSec ثانية."))
            }

            val restored = cryptoManager.restoreFromRecoveryVaultBlob(
                uid = uid,
                passphrase = passphrase,
                encryptedBlobBase64 = record.encryptedBlob,
                saltBase64 = record.saltBase64,
                ivBase64 = record.ivBase64,
                kdfIterations = record.kdfIterations.toInt()
            )
            if (restored.isSuccess) {
                ref.update(
                    mapOf(
                        "failedAttempts" to 0L,
                        "lockedUntilEpochMs" to 0L,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                publishPublicEncryptionKey(uid)
                Result.success(restored.getOrThrow().fingerprint)
            } else {
                val nextAttempts = (record.failedAttempts + 1L).coerceAtMost(20L)
                val lockUntil = if (nextAttempts >= 3L) nowMs + (nextAttempts * 30_000L) else 0L
                ref.update(
                    mapOf(
                        "failedAttempts" to nextAttempts,
                        "lockedUntilEpochMs" to lockUntil,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                Result.failure(IllegalArgumentException("عبارة الاستعادة غير صحيحة (محاولة $nextAttempts)."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // 7. Block Users, Abuse Reports & Notifications
    // -------------------------------------------------------------------------
    suspend fun blockUser(blockedUid: String, blockedPublicId: String, blockedName: String): Result<Unit> {
        var path = "blockedUsers"
        return try {
            val uid = requireUserId()
            require(blockedUid != uid) { "لا يمكنك حظر حسابك الشخصي." }
            val ref = db.collection("users").document(uid).collection("blockedUsers").document(blockedUid)
            path = ref.path
            ref.set(
                mapOf(
                    "userId" to uid,
                    "blockedUid" to blockedUid,
                    "blockedPublicId" to blockedPublicId,
                    "blockedDisplayName" to blockedName.take(60).ifBlank { blockedPublicId },
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun unblockUser(blockedUid: String): Result<Unit> {
        var path = "blockedUsers"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("blockedUsers").document(blockedUid)
            path = ref.path
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun submitReport(
        reportedUid: String,
        reportedPublicId: String,
        conversationId: String,
        reason: String,
        details: String
    ): Result<Unit> {
        val reportId = "rep_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val path = "reports/$reportId"
        return try {
            val uid = requireUserId()
            require(reportedUid != uid) { "لا يمكنك الإبلاغ عن حسابك الشخصي." }
            db.document(path).set(
                mapOf(
                    "reporterUid" to uid,
                    "reportedUid" to reportedUid,
                    "reportedPublicId" to reportedPublicId,
                    "conversationId" to conversationId.take(128),
                    "reason" to reason,
                    "details" to details.trim().take(500),
                    "status" to "submitted",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun markNotificationRead(notificationId: String): Result<Unit> {
        var path = "notifications"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("notifications").document(notificationId)
            path = ref.path
            ref.update(mapOf("read" to true, "updatedAt" to FieldValue.serverTimestamp())).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    private suspend fun createPrivacyNotificationSafe(
        recipientUid: String,
        conversationId: String,
        senderUid: String,
        senderPublicId: String,
        senderName: String,
        type: String,
        previewHidden: Boolean
    ) {
        val notifId = "ntf_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val path = "users/$recipientUid/notifications/$notifId"
        runCatching {
            val now = FieldValue.serverTimestamp()
            db.document(path).set(
                mapOf(
                    "userId" to recipientUid,
                    "conversationId" to conversationId,
                    "senderUid" to senderUid,
                    "senderPublicId" to senderPublicId,
                    "senderName" to if (previewHidden) "رسالة جديدة" else senderName.take(60),
                    "type" to type,
                    "previewHidden" to previewHidden,
                    "read" to false,
                    "createdAt" to now,
                    "updatedAt" to now
                )
            ).await()
        }.onFailure { err ->
            if (err !is CancellationException) {
                handleFirestoreError(err, OperationType.CREATE, path)
            }
        }
    }

    private fun checkClientRateLimit(): Boolean {
        val now = System.currentTimeMillis()
        while (recentSendTimestamps.isNotEmpty() && now - recentSendTimestamps.first() > 10_000L) {
            recentSendTimestamps.removeFirst()
        }
        if (recentSendTimestamps.size >= 15) return false
        recentSendTimestamps.addLast(now)
        return true
    }

    private fun logStreamErrorIfActive(error: Throwable, op: OperationType, path: String, uid: String) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val toLog = firestoreError ?: (error as? Exception)
        if (toLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(toLog, op, path)
        }
    }
}
