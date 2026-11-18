package com.example

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
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
import com.example.data.crypto.E2eeCryptoManager
import com.example.data.firestore.KatemRepository
import com.example.data.local.AppPreferences
import com.example.data.model.UiState
import com.example.data.remote.HostingerMediaClient
import com.example.ui.LocalPreviewKatemApp
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.ZangiAuthMode
import com.example.ui.auth.ZangiOnboardingScreen
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.signOutSafely
import com.example.ui.components.PrivacyShieldOverlay
import com.example.ui.screens.AddFriendScreen
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ChatsScreen
import com.example.ui.screens.ContactsScreen
import com.example.ui.screens.MyIdScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.KatemTheme
import com.example.ui.viewmodel.KatemViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val ROUTE_CHATS = "chats"
private const val ROUTE_CONTACTS = "contacts"
private const val ROUTE_MY_ID = "my_id"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_ADD_FRIEND = "add_friend"
private const val ROUTE_CHAT_DETAIL = "chat_detail/{conversationId}"

private data class NavTabItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {

    private val _pendingDeepLinkPublicId = MutableStateFlow<String?>(null)
    private val pendingDeepLinkPublicId = _pendingDeepLinkPublicId.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        extractDeepLink(intent)

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                val appPreferences = remember { AppPreferences(applicationContext) }
                val isDark by appPreferences.darkThemeFlow.collectAsStateWithLifecycle(initialValue = true)
                val deepLinkId by pendingDeepLinkPublicId.collectAsStateWithLifecycle()

                KatemTheme(darkTheme = isDark) {
                    AppNavigation(
                        deepLinkPublicId = deepLinkId,
                        onDeepLinkConsumed = { _pendingDeepLinkPublicId.value = null },
                        onApplyScreenshotProtection = { enabled ->
                            if (enabled) {
                                window.setFlags(
                                    WindowManager.LayoutParams.FLAG_SECURE,
                                    WindowManager.LayoutParams.FLAG_SECURE
                                )
                            } else {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractDeepLink(intent)
    }

    private fun extractDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme.equals("katem", ignoreCase = true) &&
            data.host.equals("add", ignoreCase = true)
        ) {
            val publicId = data.lastPathSegment?.trim()?.uppercase()
            if (!publicId.isNullOrBlank()) {
                _pendingDeepLinkPublicId.value = publicId
            }
        }
    }
}

@Composable
fun AppNavigation(
    deepLinkPublicId: String?,
    onDeepLinkConsumed: () -> Unit,
    onApplyScreenshotProtection: (Boolean) -> Unit,
    auth: FirebaseAuth = Firebase.auth
) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser
    var isLocalTestMode by remember { mutableStateOf(false) }

    when {
        user != null -> {
            // Cloud-authenticated Firebase session (Firestore strictly gated behind Google Sign-In)
            key(user.uid) {
                AuthenticatedKatemApp(
                    currentUserId = user.uid,
                    deepLinkPublicId = deepLinkPublicId,
                    onDeepLinkConsumed = onDeepLinkConsumed,
                    onApplyScreenshotProtection = onApplyScreenshotProtection
                )
            }
        }
        isLocalTestMode -> {
            // Device-only local sandbox (ZERO Firestore calls) for instant testing of Zangi ID + Password + E2EE
            LocalPreviewKatemApp(
                deepLinkPublicId = deepLinkPublicId,
                onDeepLinkConsumed = onDeepLinkConsumed,
                onApplyScreenshotProtection = onApplyScreenshotProtection,
                onExitLocalTestMode = { isLocalTestMode = false }
            )
        }
        else -> {
            AuthScreen(
                onAuthSuccess = { /* Reactive AuthStateListener transitions automatically */ },
                onEnterLocalTestMode = { isLocalTestMode = true }
            )
        }
    }
}

@Composable
private fun AuthenticatedKatemApp(
    currentUserId: String,
    deepLinkPublicId: String?,
    onDeepLinkConsumed: () -> Unit,
    onApplyScreenshotProtection: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val viewModel: KatemViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                val crypto = E2eeCryptoManager(app)
                val prefs = AppPreferences(app)
                val hostinger = HostingerMediaClient(app)
                KatemViewModel(
                    repository = KatemRepository(db = db, cryptoManager = crypto),
                    cryptoManager = crypto,
                    preferences = prefs,
                    hostingerClient = hostinger,
                    currentUserId = currentUserId
                )
            }
        }
    )

    val isInitializing by viewModel.isInitializing.collectAsStateWithLifecycle()
    val initError by viewModel.initError.collectAsStateWithLifecycle()
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    val hostingerUrl by viewModel.hostingerBaseUrl.collectAsStateWithLifecycle()
    val hostingerHealth by viewModel.hostingerHealth.collectAsStateWithLifecycle()
    val userProfileState by viewModel.userProfileState.collectAsStateWithLifecycle()
    val conversationsState by viewModel.conversationsState.collectAsStateWithLifecycle()
    val contactsState by viewModel.contactsState.collectAsStateWithLifecycle()
    val blockedUsersState by viewModel.blockedUsersState.collectAsStateWithLifecycle()
    val devicesState by viewModel.devicesState.collectAsStateWithLifecycle()
    val notificationsState by viewModel.notificationsState.collectAsStateWithLifecycle()
    val recoveryState by viewModel.recoveryDataState.collectAsStateWithLifecycle()
    val friendSearch by viewModel.friendSearch.collectAsStateWithLifecycle()
    val activeChatMeta by viewModel.activeChatMeta.collectAsStateWithLifecycle()
    val activeMessagesState by viewModel.activeMessagesState.collectAsStateWithLifecycle()
    val isUploadingMedia by viewModel.isUploadingMedia.collectAsStateWithLifecycle()
    val isZangiOnboardingCompleted by viewModel.isZangiOnboardingCompleted.collectAsStateWithLifecycle()
    val requirePasswordOnLaunch by viewModel.requirePasswordOnLaunch.collectAsStateWithLifecycle()
    val isSessionUnlocked by viewModel.isSessionUnlocked.collectAsStateWithLifecycle()
    val isManuallyLocked by viewModel.isManuallyLocked.collectAsStateWithLifecycle()
    val zangiAuthLoading by viewModel.zangiAuthLoading.collectAsStateWithLifecycle()
    val zangiAuthError by viewModel.zangiAuthError.collectAsStateWithLifecycle()

    val userProfile = (userProfileState as? UiState.Success)?.data
    val recoveryRecord = (recoveryState as? UiState.Success)?.data
    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()
    var isInBackground by remember { mutableStateOf(false) }

    // Apply Android FLAG_SECURE live from user's privacy settings
    LaunchedEffect(userProfile?.screenshotProtection) {
        onApplyScreenshotProtection(userProfile?.screenshotProtection ?: false)
    }

    // Lifecycle observer: Background privacy veil + Online presence sync
    DisposableEffect(lifecycleOwner, userProfile?.publicId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isInBackground = false
                    viewModel.setOnlinePresence(true)
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    isInBackground = true
                    viewModel.setOnlinePresence(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Collect snackbar messages
    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Handle katem://add/<publicId> deep link automatically
    LaunchedEffect(deepLinkPublicId, isInitializing) {
        if (!deepLinkPublicId.isNullOrBlank() && !isInitializing) {
            navController.navigate(ROUTE_ADD_FRIEND) { launchSingleTop = true }
        }
    }

    if ((isInitializing && userProfile == null) ||
        (userProfile != null && (isZangiOnboardingCompleted == null || recoveryState is UiState.Loading))
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "جارٍ تجهيز رقمك الفريد ومفاتيح التشفير...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    if (initError != null && userProfile == null) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = initError.orEmpty(), color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(14.dp))
                Button(onClick = { viewModel.initializeAccount() }) {
                    Text("إعادة المحاولة")
                }
            }
        }
        return
    }

    // Zangi-style Unique ID + Name + Password Registration OR Returning Password Unlock Gate
    if (userProfile != null) {
        val setupCompleted = isZangiOnboardingCompleted == true
        val needsNewRegistration = !setupCompleted && recoveryRecord == null
        val needsPasswordUnlock = recoveryRecord != null && (
            !setupCompleted || isManuallyLocked || (!isSessionUnlocked && requirePasswordOnLaunch)
        )

        if (needsNewRegistration || needsPasswordUnlock) {
            val mode = if (needsNewRegistration) {
                ZangiAuthMode.REGISTER_NEW_ID
            } else {
                ZangiAuthMode.UNLOCK_WITH_PASSWORD
            }
            ZangiOnboardingScreen(
                mode = mode,
                userProfile = userProfile,
                isLoading = zangiAuthLoading,
                errorMessage = zangiAuthError,
                onGeneratePassword = viewModel::generateRecoveryPassphrase,
                onCompleteRegistration = viewModel::completeZangiRegistration,
                onUnlockWithPassword = viewModel::unlockWithZangiPassword,
                onResetPasswordAndEnter = viewModel::resetPasswordAndEnter,
                onClearError = viewModel::clearZangiAuthError,
                onSignOut = {
                    signOutSafely(
                        credentialManager = credentialManager,
                        scope = scope,
                        onBeforeSignOut = { viewModel.setOnlinePresence(false) }
                    )
                }
            )
            return
        }
    }

    val tabs = remember {
        listOf(
            NavTabItem(ROUTE_CHATS, R.string.nav_chats, Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat, "tab_chats"),
            NavTabItem(ROUTE_CONTACTS, R.string.nav_contacts, Icons.Filled.Contacts, Icons.Outlined.Contacts, "tab_contacts"),
            NavTabItem(ROUTE_MY_ID, R.string.nav_my_id, Icons.Filled.QrCode2, Icons.Outlined.QrCode2, "tab_my_id"),
            NavTabItem(ROUTE_SETTINGS, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings, "tab_settings")
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showTopLevelNav = currentRoute in setOf(ROUTE_CHATS, ROUTE_CONTACTS, ROUTE_MY_ID, ROUTE_SETTINGS)

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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
                                        popUpTo(ROUTE_CHATS) { saveState = true }
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
                                                popUpTo(ROUTE_CHATS) { saveState = true }
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
                        startDestination = ROUTE_CHATS,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(scaffoldPadding)
                    ) {
                        composable(ROUTE_CHATS) {
                            ChatsScreen(
                                currentUserId = currentUserId,
                                userProfile = userProfile,
                                conversationsState = conversationsState,
                                onOpenConversation = { conv ->
                                    viewModel.openConversation(
                                        conversationId = conv.id,
                                        peerUid = conv.peerUid(currentUserId),
                                        peerPublicId = conv.peerPublicId(currentUserId)
                                    )
                                    navController.navigate("chat_detail/${conv.id}")
                                },
                                onOpenSavedNotes = {
                                    viewModel.openSavedNotesChat { convId ->
                                        navController.navigate("chat_detail/$convId")
                                    }
                                },
                                onOpenAddFriend = {
                                    viewModel.clearFriendSearch()
                                    navController.navigate(ROUTE_ADD_FRIEND)
                                },
                                onOpenMyId = {
                                    navController.navigate(ROUTE_MY_ID) {
                                        popUpTo(ROUTE_CHATS) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onToggleArchive = viewModel::toggleArchiveConversation,
                                onDeleteConversation = { conv -> viewModel.deleteConversation(conv) }
                            )
                        }

                        composable(ROUTE_CONTACTS) {
                            ContactsScreen(
                                contactsState = contactsState,
                                onOpenAddFriend = {
                                    viewModel.clearFriendSearch()
                                    navController.navigate(ROUTE_ADD_FRIEND)
                                },
                                onStartChat = { contact ->
                                    viewModel.startConversationWithContact(contact) { convId ->
                                        navController.navigate("chat_detail/$convId")
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

                        composable(ROUTE_MY_ID) {
                            MyIdScreen(
                                userProfile = userProfile,
                                publicFingerprint = viewModel.myPublicFingerprint,
                                onShowMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }

                        composable(ROUTE_SETTINGS) {
                            SettingsScreen(
                                userProfile = userProfile,
                                isDarkTheme = isDarkTheme,
                                hostingerBaseUrl = hostingerUrl,
                                hostingerHealth = hostingerHealth,
                                devices = (devicesState as? UiState.Success)?.data.orEmpty(),
                                blockedUsers = (blockedUsersState as? UiState.Success)?.data.orEmpty(),
                                notifications = (notificationsState as? UiState.Success)?.data.orEmpty(),
                                recoveryRecord = recoveryRecord,
                                requirePasswordOnLaunch = requirePasswordOnLaunch,
                                onToggleDarkTheme = viewModel::setDarkTheme,
                                onToggleRequirePasswordOnLaunch = viewModel::setRequirePasswordOnLaunch,
                                onLockAccountNow = viewModel::lockAccountNow,
                                onUpdateProfile = viewModel::updateProfile,
                                onUpdatePrivacy = viewModel::updatePrivacySettings,
                                onGeneratePassphrase = viewModel::generateRecoveryPassphrase,
                                onSaveRecovery = viewModel::saveRecoveryVault,
                                onRestoreRecovery = viewModel::restoreRecoveryVault,
                                onSaveHostingerUrl = viewModel::saveHostingerBaseUrl,
                                onTestHostinger = viewModel::testHostingerConnection,
                                onUnblockUser = viewModel::unblockUser,
                                onMarkNotificationRead = viewModel::markNotificationRead,
                                onSignOut = {
                                    signOutSafely(
                                        credentialManager = credentialManager,
                                        scope = scope,
                                        onBeforeSignOut = { viewModel.setOnlinePresence(false) }
                                    )
                                }
                            )
                        }

                        composable(ROUTE_ADD_FRIEND) {
                            AddFriendScreen(
                                searchState = friendSearch,
                                initialDeepLinkId = deepLinkPublicId,
                                onDeepLinkConsumed = onDeepLinkConsumed,
                                onQueryChange = viewModel::updateSearchQuery,
                                onSearch = viewModel::searchFriendByPublicId,
                                onSaveContact = viewModel::saveFoundContact,
                                onStartChat = { peer ->
                                    viewModel.startConversationWithPeer(peer) { convId ->
                                        navController.navigate("chat_detail/$convId") {
                                            popUpTo(ROUTE_ADD_FRIEND) { inclusive = true }
                                        }
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable(
                            route = ROUTE_CHAT_DETAIL,
                            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val convId = backStackEntry.arguments?.getString("conversationId").orEmpty()
                            LaunchedEffect(convId) {
                                if (convId.isNotBlank()) {
                                    viewModel.openConversation(convId)
                                }
                            }
                            DisposableEffect(convId) {
                                onDispose { viewModel.closeConversation() }
                            }
                            ChatDetailScreen(
                                currentUserId = currentUserId,
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

        // Background Privacy Veil (hides sensitive chat content in app switcher / ON_PAUSE)
        if (isInBackground && (userProfile?.blurOnBackground ?: true)) {
            PrivacyShieldOverlay()
        }
    }
}
