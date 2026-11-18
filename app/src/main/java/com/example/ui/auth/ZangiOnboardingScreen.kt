package com.example.ui.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.UserProfile
import com.example.ui.components.KatemAvatar
import com.example.ui.theme.AvatarPalette
import com.example.ui.theme.JetBrainsMonoFontFamily

enum class ZangiAuthMode {
    REGISTER_NEW_ID,
    UNLOCK_WITH_PASSWORD
}

@Composable
fun ZangiOnboardingScreen(
    mode: ZangiAuthMode,
    userProfile: UserProfile,
    isLoading: Boolean,
    errorMessage: String?,
    onGeneratePassword: () -> String,
    onCompleteRegistration: (displayName: String, bio: String, avatarColorIndex: Long, password: String) -> Unit,
    onUnlockWithPassword: (password: String) -> Unit,
    onResetPasswordAndEnter: (newPassword: String) -> Unit,
    onClearError: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    var copiedId by remember { mutableStateOf(false) }

    val initialName = remember(userProfile.displayName) {
        if (userProfile.displayName.startsWith("مستخدم كاتم")) "" else userProfile.displayName
    }
    var displayName by remember(userProfile.publicId) { mutableStateOf(initialName) }
    var bio by remember(userProfile.publicId) { mutableStateOf(userProfile.bio) }
    var selectedColorIdx by remember(userProfile.publicId) { mutableLongStateOf(userProfile.avatarColorIndex) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }

    val effectiveError = localError ?: errorMessage

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .widthIn(max = 540.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Brand Badge
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .size(64.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (mode == ZangiAuthMode.REGISTER_NEW_ID) Icons.Filled.Badge else Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (mode == ZangiAuthMode.REGISTER_NEW_ID) {
                    "تم إنشاء رقمك الفريد بنجاح!"
                } else {
                    "تسجيل الدخول إلى كاتم"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (mode == ZangiAuthMode.REGISTER_NEW_ID) {
                    "هذا هو معرّفك الشخصي الخاص ولا يتكرر أبدًا. اكتب اسمك وكلمة مرور حسابك لتسجيل الدخول فورًا."
                } else {
                    "أدخل كلمة مرور حسابك المرتبطة برقمك الفريد لفتح المحادثات ومفاتيح التشفير."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Prominent Zangi-Style Unique ID Card
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.5.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        ),
                        shape = RoundedCornerShape(22.dp)
                    )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "رقمك الفريد في كاتم (لا يتكرر)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Katem ID", userProfile.publicId))
                                    copiedId = true
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("zangi_generated_id_card")
                        ) {
                            Text(
                                text = userProfile.publicId,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Icon(
                                imageVector = if (copiedId) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                contentDescription = "نسخ المعرّف الفريد",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (copiedId) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "تم نسخ رقمك الفريد إلى الحافظة",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (mode == ZangiAuthMode.REGISTER_NEW_ID) {
                // Live Avatar Preview + Color Picker
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    KatemAvatar(
                        displayName = displayName.ifBlank { userProfile.publicId },
                        avatarUrl = userProfile.avatarUrl,
                        colorIndex = selectedColorIdx.toInt(),
                        size = 54.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "اختر لون ملفك الشخصي",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AvatarPalette.forEachIndexed { idx, color ->
                                val isSelected = selectedColorIdx.toInt() == idx
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (isSelected) 2.5.dp else 0.dp,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedColorIdx = idx.toLong() }
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Display Name Input
                OutlinedTextField(
                    value = displayName,
                    onValueChange = {
                        displayName = it.take(40)
                        localError = null
                        onClearError()
                    },
                    label = { Text("الاسم الظاهر") },
                    placeholder = { Text("مثال: محمد أحمد") },
                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_display_name_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Optional Bio
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it.take(120) },
                    label = { Text("نبذة تعريفية (اختياري)") },
                    placeholder = { Text("متاح للمراسلة الخاصة والمشفّرة") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_bio_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Password Input + Auto-generate helper
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        localError = null
                        onClearError()
                    },
                    label = { Text("كلمة المرور (8 أحرف على الأقل)") },
                    placeholder = { Text("لحماية حسابك وتشفير مفاتيحك") },
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور"
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_password_input")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            val generated = onGeneratePassword()
                            password = generated
                            confirmPassword = generated
                            passwordVisible = true
                            localError = null
                            onClearError()
                        },
                        modifier = Modifier.testTag("onboarding_generate_password_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("توليد كلمة مرور قوية تلقائيًا")
                    }
                }

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        localError = null
                        onClearError()
                    },
                    label = { Text("تأكيد كلمة المرور") },
                    leadingIcon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_confirm_password_input")
                )
            } else {
                // UNLOCK MODE (Returning user with Katem ID + Password)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(14.dp)
                    ) {
                        KatemAvatar(
                            displayName = userProfile.displayName,
                            avatarUrl = userProfile.avatarUrl,
                            colorIndex = userProfile.avatarColorIndex.toInt(),
                            size = 46.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = userProfile.displayName.ifBlank { userProfile.publicId },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "حساب محمي بتشفير PBKDF2 + AES-256-GCM",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        localError = null
                        onClearError()
                    },
                    label = { Text("كلمة مرور الحساب") },
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور"
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("zangi_unlock_password_input")
                )
            }

            if (!effectiveError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = effectiveError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Primary Submit CTA
            Button(
                onClick = {
                    if (mode == ZangiAuthMode.REGISTER_NEW_ID) {
                        val trimmedName = displayName.trim()
                        when {
                            trimmedName.length < 2 -> {
                                localError = "يرجى كتابة اسمك الظاهر (حرفين على الأقل)."
                            }
                            password.length < 8 -> {
                                localError = "يجب أن تتكون كلمة المرور من 8 أحرف على الأقل لحماية مفاتيح التشفير."
                            }
                            password != confirmPassword -> {
                                localError = "كلمتا المرور غير متطابقتين."
                            }
                            else -> {
                                localError = null
                                onCompleteRegistration(trimmedName, bio.trim(), selectedColorIdx, password)
                            }
                        }
                    } else {
                        if (password.length < 8) {
                            localError = "أدخل كلمة مرور حسابك (8 أحرف على الأقل)."
                        } else {
                            localError = null
                            onUnlockWithPassword(password)
                        }
                    }
                },
                enabled = !isLoading,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag(
                        if (mode == ZangiAuthMode.REGISTER_NEW_ID) "onboarding_submit_button"
                        else "zangi_unlock_button"
                    )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.4.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (mode == ZangiAuthMode.REGISTER_NEW_ID) "جارٍ حفظ حسابك وتشفير المفاتيح..." else "جارٍ التحقق وفتح الحساب...",
                        style = MaterialTheme.typography.labelLarge
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Login,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (mode == ZangiAuthMode.REGISTER_NEW_ID) {
                            "تسجيل الدخول والبدء فورًا"
                        } else {
                            "تسجيل الدخول بـ ID وكلمة المرور"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (mode == ZangiAuthMode.UNLOCK_WITH_PASSWORD) {
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(
                    onClick = { showResetDialog = true },
                    enabled = !isLoading
                ) {
                    Text("نسيت كلمة المرور؟ تعيين كلمة مرور جديدة")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSignOut,
                enabled = !isLoading,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("تبديل الحساب / تسجيل الخروج")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showResetDialog) {
        var newPass by remember { mutableStateOf("") }
        var resetErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("تعيين كلمة مرور جديدة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "سيتم حفظ كلمة مرور جديدة وتشفير مفاتيح الجهاز الحالي بها. إذا كنت على جهاز جديد فلن تُفك الرسائل القديمة المشفّرة بالمفتاح السابق.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newPass,
                        onValueChange = {
                            newPass = it
                            resetErr = null
                        },
                        label = { Text("كلمة المرور الجديدة (8 أحرف+)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (resetErr != null) {
                        Text(
                            text = resetErr.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPass.length < 8) {
                            resetErr = "يجب أن تكون كلمة المرور 8 أحرف على الأقل."
                        } else {
                            showResetDialog = false
                            onResetPasswordAndEnter(newPass)
                        }
                    }
                ) { Text("حفظ والدخول") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("إلغاء") }
            }
        )
    }
}
