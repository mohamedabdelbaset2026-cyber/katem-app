package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val publicId: String = "",
    val displayName: String = "",
    val bio: String = "",
    val avatarUrl: String = "",
    val avatarColorIndex: Long = 0L,
    val hideLastSeen: Boolean = false,
    val showOnlineStatus: Boolean = true,
    val hideNotificationPreview: Boolean = true,
    val screenshotProtection: Boolean = false,
    val blurOnBackground: Boolean = true,
    val disappearingDefaultSec: Long = 0L,
    @ServerTimestamp val lastSeenAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PublicIdEntry(
    @DocumentId val id: String = "",
    val publicId: String = "",
    val ownerUid: String = "",
    val displayName: String = "",
    val bio: String = "",
    val avatarUrl: String = "",
    val avatarColorIndex: Long = 0L,
    val onlineVisible: Boolean = true,
    val online: Boolean = true,
    val lastSeenVisible: Boolean = true,
    @ServerTimestamp val lastSeenAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class EncryptionKeyRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val publicKeyBase64: String = "",
    val fingerprint: String = "",
    val algorithm: String = "ECDH-P256-AES256-GCM",
    val keyVersion: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ContactEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val contactUid: String = "",
    val contactPublicId: String = "",
    val displayName: String = "",
    val bio: String = "",
    val avatarUrl: String = "",
    val avatarColorIndex: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class DeviceSession(
    @DocumentId val id: String = "",
    val userId: String = "",
    val deviceName: String = "",
    val platform: String = "android",
    val appVersion: String = "1.0",
    @ServerTimestamp val lastActiveAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class RecoveryDataRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val encryptedBlob: String = "",
    val saltBase64: String = "",
    val ivBase64: String = "",
    val kdfIterations: Long = 100000L,
    val keyVersion: Long = 1L,
    val failedAttempts: Long = 0L,
    val lockedUntilEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class BlockedUserEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val blockedUid: String = "",
    val blockedPublicId: String = "",
    val blockedDisplayName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class UserNotificationEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val conversationId: String = "",
    val senderUid: String = "",
    val senderPublicId: String = "",
    val senderName: String = "",
    val type: String = "message",
    val previewHidden: Boolean = true,
    val read: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Conversation(
    @DocumentId val id: String = "",
    val memberUids: List<String> = emptyList(),
    val member1Uid: String = "",
    val member1PublicId: String = "",
    val member1Name: String = "",
    val member1Avatar: String = "",
    val member1ColorIndex: Long = 0L,
    val member2Uid: String = "",
    val member2PublicId: String = "",
    val member2Name: String = "",
    val member2Avatar: String = "",
    val member2ColorIndex: Long = 0L,
    val lastMessageLabel: String = "",
    val lastMessageType: String = "system",
    val lastMessageSenderUid: String = "",
    @ServerTimestamp val lastMessageAt: Timestamp? = null,
    val typingUid: String = "",
    val archivedByUids: List<String> = emptyList(),
    val deletedByUids: List<String> = emptyList(),
    val disappearingSeconds: Long = 0L,
    val createdByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun isSelfChat(): Boolean = memberUids.size == 1 || member1Uid == member2Uid

    fun peerUid(myUid: String): String =
        if (isSelfChat()) myUid else if (member1Uid == myUid) member2Uid else member1Uid

    fun peerPublicId(myUid: String): String =
        if (isSelfChat()) member1PublicId else if (member1Uid == myUid) member2PublicId else member1PublicId

    fun peerName(myUid: String): String =
        if (isSelfChat()) "الرسائل المحفوظة" else if (member1Uid == myUid) member2Name else member1Name

    fun peerAvatar(myUid: String): String =
        if (isSelfChat()) member1Avatar else if (member1Uid == myUid) member2Avatar else member1Avatar

    fun peerColorIndex(myUid: String): Int =
        (if (isSelfChat()) member1ColorIndex else if (member1Uid == myUid) member2ColorIndex else member1ColorIndex).toInt()

    fun isArchivedFor(uid: String): Boolean = uid in archivedByUids

    fun isDeletedFor(uid: String): Boolean = uid in deletedByUids
}

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val conversationId: String = "",
    val memberUids: List<String> = emptyList(),
    val senderUid: String = "",
    val senderPublicId: String = "",
    val messageType: String = "text",
    val ciphertext: String = "",
    val iv: String = "",
    val encryptionScheme: String = "ECDH-P256-AES256-GCM",
    val mediaFileId: String = "",
    val mediaUrl: String = "",
    val mediaMimeType: String = "",
    val mediaFileName: String = "",
    val mediaSizeBytes: Long = 0L,
    val mediaDurationSec: Long = 0L,
    val replyToMessageId: String = "",
    val replyPreviewCiphertext: String = "",
    val replyPreviewIv: String = "",
    val deliveryStatus: String = "sent",
    val edited: Boolean = false,
    val deleted: Boolean = false,
    val expiresAtEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class DecryptedMessage(
    val raw: ChatMessage,
    val plaintext: String,
    val replyPreviewText: String,
    val decryptedSuccessfully: Boolean
)

@Keep
@IgnoreExtraProperties
data class MediaFileRecord(
    @DocumentId val id: String = "",
    val conversationId: String = "",
    val memberUids: List<String> = emptyList(),
    val uploaderUid: String = "",
    val storageProvider: String = "hostinger",
    val remotePath: String = "",
    val downloadUrl: String = "",
    val mimeType: String = "",
    val fileName: String = "",
    val sizeBytes: Long = 0L,
    val durationSec: Long = 0L,
    val encrypted: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AbuseReport(
    @DocumentId val id: String = "",
    val reporterUid: String = "",
    val reportedUid: String = "",
    val reportedPublicId: String = "",
    val conversationId: String = "",
    val reason: String = "spam",
    val details: String = "",
    val status: String = "submitted",
    @ServerTimestamp val createdAt: Timestamp? = null
)
