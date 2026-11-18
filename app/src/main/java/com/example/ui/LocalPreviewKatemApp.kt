package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.R
import com.example.data.crypto.E2eeCryptoManager
import com.example.data.local.AppPreferences
import com.example.data.remote.HostingerMediaClient
import com.example.ui.auth.ZangiAuthMode
import com.example.ui.auth.ZangiOnboardingScreen
import com.example.ui.components.PrivacyShieldOverlay
import com.example.ui.screens.AddFriendScreen
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ChatsScreen
import com.example.ui.screens.ContactsScreen
import com.example.ui.screens.MyIdScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.viewmodel.LocalTestKatemViewModel
import kotlinx.coroutines.launch

private const val LOCAL_ROUTE_CHATS = "local_chats"
private const val LOCAL_ROUTE_CONTACTS = "local_contacts"
private const val LOCAL_ROUTE_MY_ID = "local_my_id"
private const val LOCAL_ROUTE_SETTINGS = "local_settings"
private const val LOCAL_ROUTE_ADD_FRIEND = "local_add_friend"
private const val LOCAL_ROUTE_CHAT_DETAIL = "local_chat_detail/{conversationId}"

private data class LocalNavTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

/**
 * Purely device-local test shell so the user can immediately experience and test the full
 * Zangi-style unique ID generation, Name + Password vault, real ECDH+AES-GCM E2EE messaging,
 * QR scanning, voice notes, and settings without any Firestore/cloud dependency.
 */
@Composable
fun LocalPreviewKatemApp(
    deepLinkPublicId: String?,
    onDeepLinkConsumed: () -> Unit,
    onApplyScreenshotProtection: (Boolean) -> Unit,
    onExitLocalTestMode: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val viewModel: LocalTestKatemViewModel = viewModel(
        key = "local_katem_sandbox",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                LocalTestKatemViewModel(
                    appContext = app,
                    cryptoManager = E2eeCryptoManager(app),
                    preferences = AppPreferences(app),
                    hostingerClient = HostingerMediaClient(app)
                )
            }
        }
    )

    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val recoveryRecord by viewModel.recoveryRecord.collectAsStateWithLifecycle()
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    val hostingerUrl by viewModel.hostingerBaseUrl.collectAsStateWithLifecycle()
    val hostingerHealth by viewModel.hostingerHealth.collectAsStateWithLifecycle()
    val conversationsState by viewModel.conversationsState.collectAsStateWithLifecycle()
    val contactsState by viewModel.contactsState.collectAsStateWithLifecycle()
    val blockedUsers by viewModel.blockedUsers.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val friendSearch by viewModel.friendSearch.collectAsStateWithLifecycle()
    val activeChatMeta by viewModel.activeChatMeta.collectAsStateWithLifecycle()
    val activeMessagesState by viewModel.activeMessagesState.collectAsStateWithLifecycle()
    val isUploadingMedia by viewModel.isUploadingMedia.collectAsStateWithLifecycle()

    val zangiDone by viewModel.zangiRegistrationCompleted.collectAsStateWithLifecycle()
    val isSessionUnlocked by viewModel.isSessionUnlocked.collectAsStateWithLifecycle()
    val requirePasswordOnLaunch by viewModel.requirePasswordOnLaunch.collectAsStateWithLifecycle()
    val zangiAuthLoading by viewModel.zangiAuthLoading.collectAsStateWithLifecycle()
    val zangiAuthError by viewModel.zangiAuthError.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()
    var isInBackground by remember { mutableStateOf(false) }

    LaunchedEffect(userProfile.screenshotProtection) {
        onApplyScreenshotProtection(userProfile.screenshotProtection)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isInBackground = false
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isInBackground = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(deepLinkPublicId, zangiDone, isSessionUnlocked) {
        if (!deepLinkPublicId.isNullOrBlank() && zangiDone && isSessionUnlocked) {
            navController.navigate(LOCAL_ROUTE_ADD_FRIEND) { launchSingleTop = true }
        }
    }

    // Step 1: Zangi Unique ID + Name + Password Setup (or Lock Screen)
    if (!zangiDone || !isSessionUnlocked) {
        val mode = if (!zangiDone) ZangiAuthMode.REGISTER_NEW_ID else ZangiAuthMode.UNLOCK_WITH_PASSWORD
        ZangiOnboardingScreen(
            mode = mode,
            userProfile = userProfile,
            isLoading = zangiAuthLoading,
            errorMessage = zangiAuthError,
            onGeneratePassword = viewModel::generatePassword,
            onCompleteRegistration = viewModel::completeZangiRegistration,
            onUnlockWithPassword = viewModel::unlockWithZangiPassword,
            onResetPasswordAndEnter = viewModel::resetPasswordAndEnter,
            onClearError = viewModel::clearZangiAuthError,
            onSignOut = onExitLocalTestMode
        )
        return
    }

    val tabs = remember {
        listOf(
            LocalNavTab(LOCAL_ROUTE_CHATS, R.string.nav_chats, Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat, "tab_chats"),
            LocalNavTab(LOCAL_ROUTE_CONTACTS, R.string.nav_contacts, Icons.Filled.Contacts, Icons.Outlined.Contacts, "tab_contacts"),
            LocalNavTab(LOCAL_ROUTE_MY_ID, R.string.nav_my_id, Icons.Filled.QrCode2, Icons.Outlined.QrCode2, "tab_my_id"),
            LocalNavTab(LOCAL_ROUTE_SETTINGS, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings, "tab_settings")
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showTopLevelNav = currentRoute in setOf(
        LOCAL_ROUTE_CHATS,
        LOCAL_ROUTE_CONTACTS,
        LOCAL_ROUTE_MY_ID,
        LOCAL_ROUTE_SETTINGS
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Transparent Local Test Mode Strip
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "وضع الاختبار المحلي • تشفير حقيقي بدون مزامنة سحابية",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .clickable(onClick = onExitLocalTestMode)
                            .testTag("switch_to_cloud_sync_chip")
                    ) {
                        Text(
                            text = "سحابة Google",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .consumeWindowInsets(WindowInsets.statusBars)
            ) {
                val useNavigationRail = maxWidth >= 600.dp

                Row(modifier = Modifier.fillMaxSize()) {
                    if (useNavigationRail && showTopLevelNav) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            tabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(tab.route) {
                                            popUpTo(LOCAL_ROUTE_CHATS) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = stringResource(tab.labelRes)
                                        )
                                    },
                                    label = { Text(stringResource(tab.labelRes)) },
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }

                    Scaffold(
                        modifier = Modifier.weight(1f),
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                        bottomBar = {
                            if (!useNavigationRail && showTopLevelNav) {
                                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                    tabs.forEach { tab ->
                                        val selected = currentRoute == tab.route
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = {
                                                navController.navigate(tab.route) {
                                                    popUpTo(LOCAL_ROUTE_CHATS) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                    contentDescription = stringResource(tab.labelRes)
                                                )
                                            },
                                            label = { Text(stringResource(tab.labelRes)) },
                                            modifier = Modifier.testTag(tab.testTag)
                                        )
                                    }
                                }
                            }
                        }
                    ) { scaffoldPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = LOCAL_ROUTE_CHATS,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(scaffoldPadding)
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            composable(LOCAL_ROUTE_CHATS) {
                                ChatsScreen(
                                    currentUserId = viewModel.localUserId,
                                    userProfile = userProfile,
                                    conversationsState = conversationsState,
                                    onOpenConversation = { conv ->
                                        viewModel.openConversation(conv.id)
                                        navController.navigate("local_chat_detail/${conv.id}")
                                    },
                                    onOpenSavedNotes = {
                                        viewModel.openSavedNotesChat { convId ->
                                            navController.navigate("local_chat_detail/$convId")
                                        }
                                    },
                                    onOpenAddFriend = {
                                        viewModel.clearFriendSearch()
                                        navController.navigate(LOCAL_ROUTE_ADD_FRIEND)
                                    },
                                    onOpenMyId = {
                                        navController.navigate(LOCAL_ROUTE_MY_ID) {
                                            popUpTo(LOCAL_ROUTE_CHATS) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    onToggleArchive = viewModel::toggleArchiveConversation,
                                    onDeleteConversation = { conv -> viewModel.deleteConversation(conv) }
                                )
                            }

                            composable(LOCAL_ROUTE_CONTACTS) {
                                ContactsScreen(
                                    contactsState = contactsState,
                                    onOpenAddFriend = {
                                        viewModel.clearFriendSearch()
                                        navController.navigate(LOCAL_ROUTE_ADD_FRIEND)
                                    },
                                    onStartChat = { contact ->
                                        viewModel.startConversationWithContact(contact) { convId ->
                                            navController.navigate("local_chat_detail/$convId")
                                        }
                                    },
                                    onRemoveContact = viewModel::removeContact,
                                    onBlockContact = { contact ->
                                        viewModel.blockUser(contact.contactUid, contact.contactPublicId, contact.displayName)
                                    },
                                    onShowMessage = { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                )
                            }

                            composable(LOCAL_ROUTE_MY_ID) {
                                MyIdScreen(
                                    userProfile = userProfile,
                                    publicFingerprint = viewModel.myPublicFingerprint,
                                    onShowMessage = { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                )
                            }

                            composable(LOCAL_ROUTE_SETTINGS) {
                                SettingsScreen(
                                    userProfile = userProfile,
                                    isDarkTheme = isDarkTheme,
                                    hostingerBaseUrl = hostingerUrl,
                                    hostingerHealth = hostingerHealth,
                                    devices = devices,
                                    blockedUsers = blockedUsers,
                                    notifications = notifications,
                                    recoveryRecord = recoveryRecord,
                                    requirePasswordOnLaunch = requirePasswordOnLaunch,
                                    onToggleDarkTheme = viewModel::setDarkTheme,
                                    onToggleRequirePasswordOnLaunch = viewModel::setRequirePasswordOnLaunch,
                                    onLockAccountNow = viewModel::lockAccountNow,
                                    onUpdateProfile = viewModel::updateProfile,
                                    onUpdatePrivacy = viewModel::updatePrivacySettings,
                                    onGeneratePassphrase = viewModel::generatePassword,
                                    onSaveRecovery = viewModel::saveRecoveryVault,
                                    onRestoreRecovery = viewModel::restoreRecoveryVault,
                                    onSaveHostingerUrl = viewModel::saveHostingerBaseUrl,
                                    onTestHostinger = viewModel::testHostingerConnection,
                                    onUnblockUser = viewModel::unblockUser,
                                    onMarkNotificationRead = viewModel::markNotificationRead,
                                    onSignOut = onExitLocalTestMode
                                )
                            }

                            composable(LOCAL_ROUTE_ADD_FRIEND) {
                                AddFriendScreen(
                                    searchState = friendSearch,
                                    initialDeepLinkId = deepLinkPublicId,
                                    onDeepLinkConsumed = onDeepLinkConsumed,
                                    onQueryChange = viewModel::updateSearchQuery,
                                    onSearch = viewModel::searchFriendByPublicId,
                                    onSaveContact = viewModel::saveFoundContact,
                                    onStartChat = { peer ->
                                        viewModel.startConversationWithPeer(peer) { convId ->
                                            navController.navigate("local_chat_detail/$convId") {
                                                popUpTo(LOCAL_ROUTE_ADD_FRIEND) { inclusive = true }
                                            }
                                        }
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(
                                route = LOCAL_ROUTE_CHAT_DETAIL,
                                arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val convId = backStackEntry.arguments?.getString("conversationId").orEmpty()
                                LaunchedEffect(convId) {
                                    if (convId.isNotBlank()) viewModel.openConversation(convId)
                                }
                                DisposableEffect(convId) {
                                    onDispose { viewModel.closeConversation() }
                                }
                                ChatDetailScreen(
                                    currentUserId = viewModel.localUserId,
                                    chatMeta = activeChatMeta,
                                    messagesState = activeMessagesState,
                                    isUploadingMedia = isUploadingMedia,
                                    onSendText = viewModel::sendTextMessage,
                                    onEditMessage = viewModel::editMessage,
                                    onDeleteMessage = viewModel::deleteMessage,
                                    onMarkRead = viewModel::markIncomingMessagesAsRead,
                                    onTypingChanged = viewModel::updateTypingState,
                                    onSendMediaUri = viewModel::sendSelectedMediaUri,
                                    onSendVoiceFile = viewModel::sendRecordedVoiceFile,
                                    onDownloadMedia = viewModel::downloadAuthorizedMedia,
                                    onSetDisappearingTimer = viewModel::setConversationDisappearingTimer,
                                    onToggleArchive = viewModel::toggleArchiveConversation,
                                    onDeleteConversation = { conv ->
                                        viewModel.deleteConversation(conv) {
                                            navController.popBackStack()
                                        }
                                    },
                                    onBlockPeer = viewModel::blockUser,
                                    onUnblockPeer = viewModel::unblockUser,
                                    onReportPeer = viewModel::reportUser,
                                    onShowMessage = { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isInBackground && userProfile.blurOnBackground) {
            PrivacyShieldOverlay()
        }
    }
}
