package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.BlockedUserEntry
import com.example.data.model.DeviceSession
import com.example.data.model.RecoveryDataRecord
import com.example.data.model.UserNotificationEntry
import com.example.data.model.UserProfile
import com.example.data.remote.HostingerHealthStatus
import com.example.ui.components.formatTimeShort
import com.example.ui.theme.AvatarPalette
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun EditProfileDialog(
    profile: UserProfile,
    onSave: (String, String, String, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(profile.displayName) }
    var bio by remember { mutableStateOf(profile.bio) }
    var selectedColor by remember { mutableIntStateOf(profile.avatarColorIndex.toInt().coerceIn(0, 7)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل الملف الشخصي") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("الاسم الظاهر") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it.take(200) },
                    label = { Text("النبذة التعريفية (اختياري)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("لون الصورة الرمزية", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AvatarPalette.forEachIndexed { idx, color ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (selectedColor == idx) 2.5.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = idx }
                        ) {
                            if (selectedColor == idx) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(name, bio, profile.avatarUrl, selectedColor.toLong())
                    onDismiss()
                },
                enabled = name.trim().isNotEmpty()
            ) { Text("حفظ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
fun RecoveryVaultDialog(
    existingRecord: RecoveryDataRecord?,
    onGeneratePassphrase: () -> String,
    onSaveBackup: (String) -> Unit,
    onRestoreBackup: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var restoreMode by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (restoreMode) "استعادة مفتاح التشفير" else "نسخ احتياطي مشفّر للمفتاح") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (restoreMode) {
                        "أدخل عبارة الاستعادة لفك تشفير مفتاح E2EE الخاص بك (محمي ضد التخمين بتأخير متصاعد)."
                    } else {
                        "تُشفَّر نسختك الاحتياطية محليًا عبر PBKDF2 (100,000 دورة) + AES-256-GCM قبل حفظها في Firestore recoveryData."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (existingRecord != null) {
                    Text(
                        text = "الحالة: توجد نسخة احتياطية مشفّرة محفوظة بأمان.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("عبارة الاستعادة (8 أحرف على الأقل)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!restoreMode) {
                    OutlinedButton(
                        onClick = { passphrase = onGeneratePassphrase() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("توليد عبارة استعادة قوية")
                    }
                }
                if (existingRecord != null) {
                    TextButton(onClick = { restoreMode = !restoreMode }) {
                        Text(if (restoreMode) "التبديل لإنشاء نسخة جديدة" else "استعادة مفتاح من النسخة المحفوظة")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (restoreMode) onRestoreBackup(passphrase) else onSaveBackup(passphrase)
                    onDismiss()
                },
                enabled = passphrase.length >= 8
            ) {
                Text(if (restoreMode) "استعادة" else "تشفير وحفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}

@Composable
fun HostingerGatewayDialog(
    currentUrl: String,
    healthStatus: HostingerHealthStatus?,
    onSaveUrl: (String) -> Unit,
    onTestHealth: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var urlInput by remember { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ربط تخزين Hostinger الآمن") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "لا يحتوي التطبيق على أي بيانات دخول لـ Hostinger. يتصل التطبيق ببوابة PHP (في مجلد hostinger-backend) ويرسل رمز مصادقة Firebase JWT للتحقق من عضوية المحادثة قبل حفظ أو بث الملفات.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("رابط بوابة Hostinger (HTTPS)") },
                    placeholder = { Text("https://yourdomain.com/katem-api") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (healthStatus != null) {
                    Surface(
                        color = if (healthStatus.reachable) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(10.dp)
                        ) {
                            Icon(
                                imageVector = if (healthStatus.reachable) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                                contentDescription = null,
                                tint = if (healthStatus.reachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = healthStatus.message,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { onTestHealth(urlInput) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("اختبار الاتصال بـ health.php") }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSaveUrl(urlInput)
                onDismiss()
            }) { Text("حفظ الرابط") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}

@Composable
fun DevicesListDialog(
    devices: List<DeviceSession>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("الأجهزة المرتبطة (${devices.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (devices.isEmpty()) {
                    Text("لا توجد أجهزة مسجّلة.", style = MaterialTheme.typography.bodySmall)
                } else {
                    devices.forEach { dev ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(dev.deviceName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "Android • إصدار ${dev.appVersion} • آخر نشاط ${formatTimeShort(dev.lastActiveAt?.toDate())}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

@Composable
fun BlockedUsersDialog(
    blocked: List<BlockedUserEntry>,
    onUnblock: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("المستخدمون المحظورون (${blocked.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (blocked.isEmpty()) {
                    Text("قائمة الحظر فارغة.", style = MaterialTheme.typography.bodySmall)
                } else {
                    blocked.forEach { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.blockedDisplayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = entry.blockedPublicId,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onUnblock(entry.blockedUid) }) {
                                Text("إلغاء الحظر")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

@Composable
fun NotificationsDialog(
    notifications: List<UserNotificationEntry>,
    onMarkRead: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("الإشعارات الآمنة (${notifications.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notifications.isEmpty()) {
                    Text("لا توجد إشعارات جديدة.", style = MaterialTheme.typography.bodySmall)
                } else {
                    notifications.take(10).forEach { notif ->
                        Surface(
                            onClick = { if (!notif.read) onMarkRead(notif.id) },
                            color = if (notif.read) {
                                MaterialTheme.colorScheme.surfaceContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = if (notif.previewHidden) "إشعار مشفّر جديد" else notif.senderName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${notif.senderPublicId} • ${formatTimeShort(notif.createdAt?.toDate())}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}
