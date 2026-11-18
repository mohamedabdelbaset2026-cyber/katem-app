package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BlockedUserEntry
import com.example.data.model.DeviceSession
import com.example.data.model.RecoveryDataRecord
import com.example.data.model.UserNotificationEntry
import com.example.data.model.UserProfile
import com.example.data.remote.HostingerHealthStatus
import com.example.ui.components.KatemAvatar
import com.example.ui.components.formatDisappearingLabel
import com.example.ui.theme.JetBrainsMonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    userProfile: UserProfile?,
    isDarkTheme: Boolean,
    hostingerBaseUrl: String,
    hostingerHealth: HostingerHealthStatus?,
    devices: List<DeviceSession>,
    blockedUsers: List<BlockedUserEntry>,
    notifications: List<UserNotificationEntry>,
    recoveryRecord: RecoveryDataRecord?,
    requirePasswordOnLaunch: Boolean,
    onToggleDarkTheme: (Boolean) -> Unit,
    onToggleRequirePasswordOnLaunch: (Boolean) -> Unit,
    onLockAccountNow: () -> Unit,
    onUpdateProfile: (String, String, String, Long) -> Unit,
    onUpdatePrivacy: (Boolean, Boolean, Boolean, Boolean, Boolean, Long) -> Unit,
    onGeneratePassphrase: () -> String,
    onSaveRecovery: (String) -> Unit,
    onRestoreRecovery: (String) -> Unit,
    onSaveHostingerUrl: (String) -> Unit,
    onTestHostinger: (String) -> Unit,
    onUnblockUser: (String) -> Unit,
    onMarkNotificationRead: (String) -> Unit,
    onSignOut: () -> Unit
) {
    var showEditProfile by remember { mutableStateOf(false) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var showHostingerDialog by remember { mutableStateOf(false) }
    var showDevicesDialog by remember { mutableStateOf(false) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    var showNotificationsDialog by remember { mutableStateOf(false) }
    var showDefaultTimerDialog by remember { mutableStateOf(false) }

    val unreadNotificationsCount = remember(notifications) { notifications.count { !it.read } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // Profile Card
            if (userProfile != null) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        KatemAvatar(
                            displayName = userProfile.displayName,
                            avatarUrl = userProfile.avatarUrl,
                            colorIndex = userProfile.avatarColorIndex.toInt(),
                            size = 58.dp
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = userProfile.displayName.ifBlank { "مستخدم كاتم" },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = userProfile.publicId,
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (userProfile.bio.isNotBlank()) {
                                Text(
                                    text = userProfile.bio,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = { showEditProfile = true },
                            modifier = Modifier.testTag("edit_profile_button")
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "تعديل الملف الشخصي")
                        }
                    }
                }
            }

            // Appearance Section
            SettingsSectionCard(title = "المظهر") {
                SettingsSwitchRow(
                    icon = Icons.Filled.DarkMode,
                    title = "الوضع الليلي",
                    subtitle = if (isDarkTheme) "مظهر ليلي مريح للعين" else "مظهر نهاري واضح",
                    checked = isDarkTheme,
                    onCheckedChange = onToggleDarkTheme,
                    testTag = "dark_mode_switch"
                )
            }

            // Privacy & OS Security Section
            if (userProfile != null) {
                SettingsSectionCard(title = "الخصوصية وحماية الشاشة") {
                    SettingsSwitchRow(
                        icon = Icons.Filled.Screenshot,
                        title = "منع لقطات وتسجيل الشاشة (FLAG_SECURE)",
                        subtitle = "يمنع التقاط لقطات الشاشة داخل التطبيق على أندرويد",
                        checked = userProfile.screenshotProtection,
                        onCheckedChange = { checked ->
                            onUpdatePrivacy(
                                userProfile.hideLastSeen,
                                userProfile.showOnlineStatus,
                                userProfile.hideNotificationPreview,
                                checked,
                                userProfile.blurOnBackground,
                                userProfile.disappearingDefaultSec
                            )
                        },
                        testTag = "screenshot_protection_switch"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchRow(
                        icon = Icons.Filled.VisibilityOff,
                        title = "إخفاء المحتوى في الخلفية",
                        subtitle = "يغطي شاشة المحادثات تلقائيًا عند الانتقال لتطبيق آخر",
                        checked = userProfile.blurOnBackground,
                        onCheckedChange = { checked ->
                            onUpdatePrivacy(
                                userProfile.hideLastSeen,
                                userProfile.showOnlineStatus,
                                userProfile.hideNotificationPreview,
                                userProfile.screenshotProtection,
                                checked,
                                userProfile.disappearingDefaultSec
                            )
                        },
                        testTag = "blur_background_switch"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchRow(
                        icon = Icons.Filled.PrivacyTip,
                        title = "إخفاء آخر ظهور",
                        subtitle = "لا يظهر وقت آخر نشاط لك لجهات الاتصال",
                        checked = userProfile.hideLastSeen,
                        onCheckedChange = { checked ->
                            onUpdatePrivacy(
                                checked,
                                userProfile.showOnlineStatus,
                                userProfile.hideNotificationPreview,
                                userProfile.screenshotProtection,
                                userProfile.blurOnBackground,
                                userProfile.disappearingDefaultSec
                            )
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchRow(
                        icon = Icons.Filled.PrivacyTip,
                        title = "إظهار حالة الاتصال (متصل الآن)",
                        subtitle = "التحكم في ظهور مؤشر الاتصال المباشر",
                        checked = userProfile.showOnlineStatus,
                        onCheckedChange = { checked ->
                            onUpdatePrivacy(
                                userProfile.hideLastSeen,
                                checked,
                                userProfile.hideNotificationPreview,
                                userProfile.screenshotProtection,
                                userProfile.blurOnBackground,
                                userProfile.disappearingDefaultSec
                            )
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchRow(
                        icon = Icons.Filled.Notifications,
                        title = "إخفاء محتوى الإشعارات",
                        subtitle = "يخفي اسم المرسل ومحتوى الرسالة من الإشعارات",
                        checked = userProfile.hideNotificationPreview,
                        onCheckedChange = { checked ->
                            onUpdatePrivacy(
                                userProfile.hideLastSeen,
                                userProfile.showOnlineStatus,
                                checked,
                                userProfile.screenshotProtection,
                                userProfile.blurOnBackground,
                                userProfile.disappearingDefaultSec
                            )
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsActionRow(
                        icon = Icons.Filled.Timer,
                        title = "الرسائل ذاتية الاختفاء الافتراضية",
                        subtitle = formatDisappearingLabel(userProfile.disappearingDefaultSec),
                        onClick = { showDefaultTimerDialog = true }
                    )
                }
            }

            // E2EE Recovery, Hostinger Storage & Account Safety
            SettingsSectionCard(title = "التشفير، الاستعادة وتخزين Hostinger") {
                if (recoveryRecord != null) {
                    SettingsSwitchRow(
                        icon = Icons.Filled.Lock,
                        title = "طلب كلمة المرور عند فتح التطبيق",
                        subtitle = "يقفل المحادثات برقمك الفريد (KTM-ID) وكلمة المرور عند كل تشغيل",
                        checked = requirePasswordOnLaunch,
                        onCheckedChange = onToggleRequirePasswordOnLaunch
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                SettingsActionRow(
                    icon = Icons.Filled.Key,
                    title = "استعادة الحساب ومفتاح E2EE",
                    subtitle = if (recoveryRecord != null) "نسخة مشفّرة محفوظة (PBKDF2 + AES-GCM)" else "أنشئ كلمة مرور لحماية مفاتيحك",
                    onClick = { showRecoveryDialog = true },
                    testTag = "recovery_vault_row"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Filled.CloudQueue,
                    title = "بوابة تخزين Hostinger للوسائط",
                    subtitle = hostingerBaseUrl.ifBlank { "غير مهيأ بعد" },
                    onClick = { showHostingerDialog = true },
                    testTag = "hostinger_settings_row"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Filled.Devices,
                    title = "الأجهزة المرتبطة",
                    subtitle = "${devices.size} جهاز نشط",
                    onClick = { showDevicesDialog = true }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Filled.Block,
                    title = "المستخدمون المحظورون",
                    subtitle = "${blockedUsers.size} مستخدم محظور",
                    onClick = { showBlockedDialog = true }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Filled.Notifications,
                    title = "سجل الإشعارات الآمنة",
                    subtitle = if (unreadNotificationsCount > 0) "$unreadNotificationsCount إشعار غير مقروء" else "لا توجد إشعارات غير مقروءة",
                    onClick = { showNotificationsDialog = true }
                )
            }

            if (recoveryRecord != null) {
                OutlinedButton(
                    onClick = onLockAccountNow,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("lock_account_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "قفل الحساب (الدخول بالمعرّف وكلمة المرور)",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            OutlinedButton(
                onClick = onSignOut,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sign_out_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_sign_out),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showEditProfile && userProfile != null) {
        EditProfileDialog(
            profile = userProfile,
            onSave = onUpdateProfile,
            onDismiss = { showEditProfile = false }
        )
    }

    if (showRecoveryDialog) {
        RecoveryVaultDialog(
            existingRecord = recoveryRecord,
            onGeneratePassphrase = onGeneratePassphrase,
            onSaveBackup = onSaveRecovery,
            onRestoreBackup = onRestoreRecovery,
            onDismiss = { showRecoveryDialog = false }
        )
    }

    if (showHostingerDialog) {
        HostingerGatewayDialog(
            currentUrl = hostingerBaseUrl,
            healthStatus = hostingerHealth,
            onSaveUrl = onSaveHostingerUrl,
            onTestHealth = onTestHostinger,
            onDismiss = { showHostingerDialog = false }
        )
    }

    if (showDevicesDialog) {
        DevicesListDialog(devices = devices, onDismiss = { showDevicesDialog = false })
    }

    if (showBlockedDialog) {
        BlockedUsersDialog(
            blocked = blockedUsers,
            onUnblock = onUnblockUser,
            onDismiss = { showBlockedDialog = false }
        )
    }

    if (showNotificationsDialog) {
        NotificationsDialog(
            notifications = notifications,
            onMarkRead = onMarkNotificationRead,
            onDismiss = { showNotificationsDialog = false }
        )
    }

    if (showDefaultTimerDialog && userProfile != null) {
        val options = listOf(0L, 30L, 300L, 3600L, 86400L, 604800L)
        AlertDialog(
            onDismissRequest = { showDefaultTimerDialog = false },
            title = { Text("المؤقت الافتراضي للرسائل ذاتية الاختفاء") },
            text = {
                Column {
                    options.forEach { sec ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = userProfile.disappearingDefaultSec == sec,
                                onClick = {
                                    onUpdatePrivacy(
                                        userProfile.hideLastSeen,
                                        userProfile.showOnlineStatus,
                                        userProfile.hideNotificationPreview,
                                        userProfile.screenshotProtection,
                                        userProfile.blurOnBackground,
                                        sec
                                    )
                                    showDefaultTimerDialog = false
                                }
                            )
                            Text(formatDisappearingLabel(sec))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDefaultTimerDialog = false }) { Text("إغلاق") }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String? = null
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = if (testTag != null) Modifier.fillMaxWidth().testTag(testTag) else Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
