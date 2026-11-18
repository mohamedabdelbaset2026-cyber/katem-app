package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.model.Conversation
import com.example.data.model.DecryptedMessage
import com.example.data.model.UiState
import com.example.ui.components.EncryptedStatusPill
import com.example.ui.components.KatemAvatar
import com.example.ui.components.VoiceNotePlayerBar
import com.example.ui.components.formatDisappearingLabel
import com.example.ui.components.formatDuration
import com.example.ui.components.formatTimeShort
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalKatemChatColors
import com.example.ui.viewmodel.ActiveChatMeta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    currentUserId: String,
    chatMeta: ActiveChatMeta,
    messagesState: UiState<List<DecryptedMessage>>,
    isUploadingMedia: Boolean,
    onSendText: (String, DecryptedMessage?) -> Unit,
    onEditMessage: (String, String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onMarkRead: (List<DecryptedMessage>) -> Unit,
    onTypingChanged: (Boolean) -> Unit,
    onSendMediaUri: (Uri, String, String) -> Unit,
    onSendVoiceFile: (File, Long) -> Unit,
    onDownloadMedia: suspend (String, String) -> Result<File>,
    onSetDisappearingTimer: (String, Long) -> Unit,
    onToggleArchive: (Conversation) -> Unit,
    onDeleteConversation: (Conversation) -> Unit,
    onBlockPeer: (String, String, String) -> Unit,
    onUnblockPeer: (String) -> Unit,
    onReportPeer: (String, String, String, String, String) -> Unit,
    onShowMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val conversation = chatMeta.conversation

    var inputText by remember { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<DecryptedMessage?>(null) }
    var editingTarget by remember { mutableStateOf<DecryptedMessage?>(null) }
    var selectedMessageForActions by remember { mutableStateOf<DecryptedMessage?>(null) }

    var showInChatSearch by remember { mutableStateOf(false) }
    var inChatQuery by remember { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }
    var showSafetyDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }

    // Voice recording state
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableLongStateOf(0L) }
    var recorderRef by remember { mutableStateOf<MediaRecorder?>(null) }
    var activeAudioFile by remember { mutableStateOf<File?>(null) }

    fun stopAndReleaseRecorder(sendIfValid: Boolean) {
        val rec = recorderRef
        val file = activeAudioFile
        val dur = recordingSeconds.coerceAtLeast(1L)
        recorderRef = null
        activeAudioFile = null
        isRecording = false
        recordingSeconds = 0L
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        if (sendIfValid && file != null && file.exists() && file.length() > 0L) {
            onSendVoiceFile(file, dur)
        } else {
            runCatching { file?.delete() }
        }
    }

    fun startVoiceRecording() {
        val outFile = File(context.cacheDir, "katem_voice_${System.currentTimeMillis()}.m4a")
        runCatching {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(96_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(outFile.absolutePath)
            rec.prepare()
            rec.start()
            recorderRef = rec
            activeAudioFile = outFile
            recordingSeconds = 0L
            isRecording = true
        }.onFailure {
            runCatching { outFile.delete() }
            onShowMessage("تعذر بدء تسجيل الصوت على هذا الجهاز.")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopAndReleaseRecorder(sendIfValid = false)
            onTypingChanged(false)
        }
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000L)
            recordingSeconds++
        }
    }

    // Debounced typing indicator
    LaunchedEffect(inputText) {
        if (inputText.isNotBlank()) {
            onTypingChanged(true)
            delay(3500L)
            onTypingChanged(false)
        } else {
            onTypingChanged(false)
        }
    }

    // Zero-permission Android Photo Picker (Images)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { onSendMediaUri(it, "image", "image/jpeg") }
    }

    // Zero-permission Android Photo Picker (Videos)
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { onSendMediaUri(it, "video", "video/mp4") }
    }

    // Zero-permission Storage Access Framework Document/PDF Picker
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onSendMediaUri(it, "document", "application/pdf") }
    }

    // Runtime RECORD_AUDIO permission for voice notes only
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceRecording()
        else onShowMessage("يتطلب تسجيل الرسائل الصوتية إذن الميكروفون.")
    }

    val allMessages = (messagesState as? UiState.Success)?.data.orEmpty()
    LaunchedEffect(allMessages.size) {
        if (allMessages.isNotEmpty()) {
            onMarkRead(allMessages)
            listState.animateScrollToItem(allMessages.lastIndex)
        }
    }

    val displayedMessages = remember(allMessages, inChatQuery) {
        val q = inChatQuery.trim().lowercase()
        if (q.isEmpty()) allMessages
        else allMessages.filter { it.plaintext.lowercase().contains(q) }
    }

    val peerName = conversation?.peerName(currentUserId) ?: "محادثة مشفّرة"
    val peerPublicId = conversation?.peerPublicId(currentUserId).orEmpty()
    val peerUid = conversation?.peerUid(currentUserId).orEmpty()
    val isSelfChat = conversation?.isSelfChat() == true
    val isPeerTyping = conversation != null && conversation.typingUid.isNotBlank() && conversation.typingUid != currentUserId

    val subtitleText = when {
        isSelfChat -> "مساحة ملاحظات مشفّرة بالكامل"
        isPeerTyping -> stringResource(R.string.chat_typing)
        chatMeta.peerProfile?.onlineVisible == true && chatMeta.peerProfile.online -> stringResource(R.string.chat_online)
        chatMeta.peerProfile?.lastSeenVisible == true && chatMeta.peerProfile.lastSeenAt != null ->
            "آخر ظهور ${formatTimeShort(chatMeta.peerProfile.lastSeenAt.toDate())}"
        else -> peerPublicId
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (conversation != null) {
                            KatemAvatar(
                                displayName = peerName,
                                avatarUrl = conversation.peerAvatar(currentUserId),
                                colorIndex = conversation.peerColorIndex(currentUserId),
                                size = 38.dp,
                                isSelfNotes = isSelfChat,
                                showOnlineBadge = !isSelfChat && chatMeta.peerProfile?.onlineVisible == true && chatMeta.peerProfile.online
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column {
                            Text(
                                text = peerName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isPeerTyping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            showInChatSearch = !showInChatSearch
                            if (!showInChatSearch) inChatQuery = ""
                        },
                        modifier = Modifier.testTag("chat_search_button")
                    ) {
                        Icon(
                            imageVector = if (showInChatSearch) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = stringResource(R.string.cd_search)
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier.testTag("chat_more_menu")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.cd_more_options)
                            )
                        }
                        DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("بصمة التشفير (E2EE)") },
                                leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    showSafetyDialog = true
                                }
                            )
                            if (conversation != null) {
                                DropdownMenuItem(
                                    text = { Text("الرسائل ذاتية الاختفاء (${formatDisappearingLabel(conversation.disappearingSeconds)})") },
                                    leadingIcon = { Icon(Icons.Filled.Timer, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showTimerDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (conversation.isArchivedFor(currentUserId)) "إلغاء الأرشفة" else "أرشفة المحادثة") },
                                    onClick = {
                                        showOverflowMenu = false
                                        onToggleArchive(conversation)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("حذف المحادثة", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onDeleteConversation(conversation)
                                    }
                                )
                            }
                            if (!isSelfChat && peerUid.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text(if (chatMeta.isPeerBlocked) "إلغاء حظر المستخدم" else "حظر المستخدم") },
                                    leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        if (chatMeta.isPeerBlocked) onUnblockPeer(peerUid)
                                        else onBlockPeer(peerUid, peerPublicId, peerName)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("الإبلاغ عن الحساب", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        showReportDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding()
        ) {
            AnimatedVisibility(visible = showInChatSearch) {
                OutlinedTextField(
                    value = inChatQuery,
                    onValueChange = { inChatQuery = it },
                    placeholder = { Text("ابحث داخل الرسائل المفكوكة...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("in_chat_search_input")
                )
            }

            if (isUploadingMedia) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Messages List
            Box(modifier = Modifier.weight(1f)) {
                when (messagesState) {
                    is UiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    is UiState.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = messagesState.message, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    is UiState.Success -> {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item(key = "e2ee_header") {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                ) {
                                    EncryptedStatusPill(text = "تشفير حقيقي ECDH P-256 + AES-256-GCM")
                                }
                            }
                            items(displayedMessages, key = { it.raw.id }) { item ->
                                ChatMessageBubble(
                                    message = item,
                                    isOutgoing = item.raw.senderUid == currentUserId,
                                    onLongClick = { selectedMessageForActions = item },
                                    onDownloadMedia = onDownloadMedia,
                                    onOpenDownloadedFile = { url, name ->
                                        scope.launch {
                                            onDownloadMedia(url, name)
                                                .onSuccess { file ->
                                                    onShowMessage("تم تنزيل الملف بأمان (${file.name} - ${file.length() / 1024} KB).")
                                                }
                                                .onFailure {
                                                    onShowMessage(it.localizedMessage ?: "تعذر تنزيل الملف من Hostinger.")
                                                }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Reply / Edit Banner
            AnimatedVisibility(visible = replyTarget != null || editingTarget != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (editingTarget != null) Icons.Filled.Edit else Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (editingTarget != null) "تعديل الرسالة" else "رد على رسالة",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = (editingTarget ?: replyTarget)?.plaintext.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = {
                            replyTarget = null
                            editingTarget = null
                            inputText = ""
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "إلغاء")
                        }
                    }
                }
            }

            // Blocked notice or Composer Bar
            if (chatMeta.isPeerBlocked) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "هذا المستخدم محظور حاليًا.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { onUnblockPeer(peerUid) }) {
                            Text("إلغاء الحظر")
                        }
                    }
                }
            } else if (isRecording) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        IconButton(onClick = { stopAndReleaseRecorder(sendIfValid = false) }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "إلغاء التسجيل", tint = MaterialTheme.colorScheme.error)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "جارٍ التسجيل الصوتي  ${formatDuration(recordingSeconds)}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { stopAndReleaseRecorder(sendIfValid = true) },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "إرسال التسجيل الصوتي",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        IconButton(
                            onClick = { showAttachmentSheet = true },
                            modifier = Modifier.testTag("chat_attach_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AttachFile,
                                contentDescription = stringResource(R.string.cd_attach_media),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                            maxLines = 4,
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_message_input")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        if (inputText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    val textToSend = inputText.trim()
                                    val editItem = editingTarget
                                    if (editItem != null) {
                                        onEditMessage(editItem.raw.id, textToSend)
                                        editingTarget = null
                                    } else {
                                        onSendText(textToSend, replyTarget)
                                        replyTarget = null
                                    }
                                    inputText = ""
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .testTag("chat_send_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.cd_send_message),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) startVoiceRecording()
                                    else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .testTag("chat_voice_record_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Mic,
                                    contentDescription = stringResource(R.string.cd_record_voice),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Message Action Dialog (Reply, Edit, Copy, Delete)
    selectedMessageForActions?.let { msg ->
        val isOwn = msg.raw.senderUid == currentUserId
        AlertDialog(
            onDismissRequest = { selectedMessageForActions = null },
            title = { Text("خيارات الرسالة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!msg.raw.deleted) {
                        TextButton(
                            onClick = {
                                replyTarget = msg
                                editingTarget = null
                                selectedMessageForActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("رد على الرسالة", modifier = Modifier.weight(1f))
                        }
                        TextButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Message", msg.plaintext))
                                onShowMessage("تم نسخ نص الرسالة.")
                                selectedMessageForActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("نسخ النص", modifier = Modifier.weight(1f))
                        }
                        if (isOwn && msg.raw.messageType == "text") {
                            TextButton(
                                onClick = {
                                    editingTarget = msg
                                    replyTarget = null
                                    inputText = msg.plaintext
                                    selectedMessageForActions = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("تعديل الرسالة", modifier = Modifier.weight(1f))
                            }
                        }
                        if (isOwn) {
                            TextButton(
                                onClick = {
                                    onDeleteMessage(msg.raw.id)
                                    selectedMessageForActions = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("حذف الرسالة للجميع", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMessageForActions = null }) { Text("إغلاق") }
            }
        )
    }

    // Media Attachment Bottom Sheet (Zero-Permission Android Photo Picker & SAF)
    if (showAttachmentSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachmentSheet = false }) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding()
            ) {
                Text(
                    text = "إرسال وسائط عبر خادم Hostinger الآمن",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "يتم اختيار الملفات عبر منتقي أندرويد الآمن بدون صلاحيات معرض شاملة، وتُرفع مباشرة إلى بوابة Hostinger الخاصة بك.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AttachmentOptionTile(
                        icon = Icons.Filled.Image,
                        label = "صورة",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentSheet = false
                            imagePickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    AttachmentOptionTile(
                        icon = Icons.Filled.Videocam,
                        label = "فيديو",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentSheet = false
                            videoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        }
                    )
                    AttachmentOptionTile(
                        icon = Icons.Filled.Description,
                        label = "PDF / مستند",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentSheet = false
                            documentPickerLauncher.launch(
                                arrayOf(
                                    "application/pdf",
                                    "application/msword",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "text/plain"
                                )
                            )
                        }
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }

    // Disappearing Messages Timer Dialog
    if (showTimerDialog && conversation != null) {
        val options = listOf(0L, 30L, 300L, 3600L, 86400L, 604800L)
        AlertDialog(
            onDismissRequest = { showTimerDialog = false },
            title = { Text("الرسائل ذاتية الاختفاء") },
            text = {
                Column {
                    options.forEach { sec ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = conversation.disappearingSeconds == sec,
                                onClick = {
                                    onSetDisappearingTimer(conversation.id, sec)
                                    showTimerDialog = false
                                }
                            )
                            Text(text = formatDisappearingLabel(sec), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTimerDialog = false }) { Text("إغلاق") }
            }
        )
    }

    // E2EE Safety Number Verification Dialog
    if (showSafetyDialog) {
        AlertDialog(
            onDismissRequest = { showSafetyDialog = false },
            title = { Text("التحقق من تشفير المحادثة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "قارن رقم الأمان المشترك أدناه مع الطرف الآخر للتأكد من عدم وجود أي وسيط. الخوارزمية المستخدمة: ECDH P-256 + HKDF-SHA256 + AES-256-GCM.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = chatMeta.safetyNumber.ifBlank { "جارٍ الحساب..." },
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSafetyDialog = false }) { Text("تم") }
            }
        )
    }

    // Report User Dialog
    if (showReportDialog && conversation != null) {
        var selectedReason by remember { mutableStateOf("spam") }
        var details by remember { mutableStateOf("") }
        val reasons = listOf(
            "spam" to "رسائل مزعجة (Spam)",
            "harassment" to "مضايقة أو إساءة",
            "impersonation" to "انتحال شخصية",
            "scam" to "احتيال",
            "other" to "سبب آخر"
        )
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("الإبلاغ عن الحساب ($peerPublicId)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    reasons.forEach { (code, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedReason == code, onClick = { selectedReason = code })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    OutlinedTextField(
                        value = details,
                        onValueChange = { details = it.take(400) },
                        label = { Text("تفاصيل إضافية (اختياري)") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onReportPeer(peerUid, peerPublicId, conversation.id, selectedReason, details)
                    showReportDialog = false
                }) { Text("إرسال البلاغ", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text("إلغاء") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageBubble(
    message: DecryptedMessage,
    isOutgoing: Boolean,
    onLongClick: () -> Unit,
    onDownloadMedia: suspend (String, String) -> Result<File>,
    onOpenDownloadedFile: (String, String) -> Unit
) {
    val chatColors = LocalKatemChatColors.current
    val raw = message.raw
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isOutgoing) 18.dp else 4.dp,
        bottomEnd = if (isOutgoing) 4.dp else 18.dp
    )
    val textColor = if (isOutgoing) chatColors.outgoingText else chatColors.incomingText

    Row(
        horizontalArrangement = if (isOutgoing) Arrangement.Start else Arrangement.End,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(shape)
                .then(
                    if (isOutgoing) {
                        Modifier.background(
                            Brush.linearGradient(listOf(chatColors.outgoingStart, chatColors.outgoingEnd))
                        )
                    } else {
                        Modifier
                            .background(chatColors.incomingBg)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    }
                )
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
                .padding(horizontal = 13.dp, vertical = 9.dp)
        ) {
            Column {
                if (raw.replyToMessageId.isNotBlank() && !raw.deleted) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = message.replyPreviewText.ifBlank { "رد على رسالة" },
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }

                if (raw.deleted) {
                    Text(
                        text = stringResource(R.string.chat_message_deleted),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = textColor.copy(alpha = 0.7f)
                    )
                } else when (raw.messageType) {
                    "voice" -> {
                        VoiceNotePlayerBar(
                            downloadUrl = raw.mediaUrl,
                            fileName = raw.mediaFileName.ifBlank { "voice.m4a" },
                            durationSec = raw.mediaDurationSec,
                            isOutgoing = isOutgoing,
                            onDownloadFile = onDownloadMedia
                        )
                    }

                    "image", "video", "document" -> {
                        val icon = when (raw.messageType) {
                            "image" -> Icons.Filled.Image
                            "video" -> Icons.Filled.Videocam
                            else -> Icons.Filled.Description
                        }
                        Surface(
                            onClick = { onOpenDownloadedFile(raw.mediaUrl, raw.mediaFileName) },
                            color = Color.Black.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(10.dp)
                            ) {
                                Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = raw.mediaFileName.ifBlank { message.plaintext },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textColor,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Hostinger • ${(raw.mediaSizeBytes / 1024).coerceAtLeast(1)} KB • اضغط للتنزيل الآمن",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = textColor.copy(alpha = 0.78f)
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        Text(
                            text = message.plaintext,
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    if (raw.expiresAtEpochMs > 0L) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = "رسالة مؤقتة",
                            tint = textColor.copy(alpha = 0.72f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    if (raw.edited && !raw.deleted) {
                        Text(
                            text = stringResource(R.string.chat_message_edited),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.72f)
                        )
                    }
                    Text(
                        text = formatTimeShort(raw.createdAt?.toDate()),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.75f)
                    )
                    if (isOutgoing) {
                        val tickIcon = if (raw.deliveryStatus == "sent") Icons.Filled.Check else Icons.Filled.DoneAll
                        val tickColor = if (raw.deliveryStatus == "read") chatColors.readTick else textColor.copy(alpha = 0.75f)
                        Icon(
                            imageVector = tickIcon,
                            contentDescription = raw.deliveryStatus,
                            tint = tickColor,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentOptionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .height(92.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
