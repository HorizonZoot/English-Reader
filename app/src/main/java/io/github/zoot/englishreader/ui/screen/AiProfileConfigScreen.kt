package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Scaffold
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.ai.AI_DRAFT_CONNECTION_TEST_ID
import io.github.zoot.englishreader.data.ai.AiClientResult
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.repository.ProfileMutationResult
import io.github.zoot.englishreader.util.toUiMessage
import io.github.zoot.englishreader.viewmodel.AiProfileValidation
import io.github.zoot.englishreader.viewmodel.ProfileActionResult
import io.github.zoot.englishreader.viewmodel.SettingsViewModel
import java.util.UUID

private val ProfileCardShape = RoundedCornerShape(16.dp)
private val ProfileControlShape = RoundedCornerShape(12.dp)
private val ProfileEditorShape = RoundedCornerShape(24.dp)

private data class PendingConnectionTest(
    val requestId: String,
    val profileId: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProfileConfigScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val inFlightIds by viewModel.connectionTestInFlightProfileIds.collectAsStateWithLifecycle()
    val draftTestInFlight by viewModel.draftConnectionTestInFlight.collectAsStateWithLifecycle()
    val profileMutationInFlight by viewModel.profileMutationInFlight.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var editorOpen by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<AiProviderProfile?>(null) }
    var actionProfile by remember { mutableStateOf<AiProviderProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<AiProviderProfile?>(null) }
    var showClearCache by remember { mutableStateOf(false) }
    var showDeleteAll by remember { mutableStateOf(false) }

    var displayName by remember { mutableStateOf("") }
    var providerTemplate by remember { mutableStateOf(AiProviderTemplate.DEEPSEEK) }
    var baseUrl by remember { mutableStateOf(AiProviderTemplate.DEEPSEEK.defaultBaseUrl.orEmpty()) }
    var modelId by remember { mutableStateOf(AiProviderTemplate.DEEPSEEK.defaultModelId.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var temperature by remember { mutableStateOf("0.2") }
    var editorConnectionResult by remember { mutableStateOf<AiClientResult?>(null) }
    var validationAttempted by remember { mutableStateOf(false) }
    val pendingConnectionTest = remember {
        mutableStateOf<PendingConnectionTest?>(null)
    }

    fun invalidateConnectionTestResult() {
        pendingConnectionTest.value = null
        editorConnectionResult = null
    }

    fun openEditor(profile: AiProviderProfile?) {
        editingProfile = profile
        displayName = profile?.displayName ?: context.getString(R.string.settings_provider_deepseek)
        validationAttempted = false
        providerTemplate = profile?.providerTemplate ?: AiProviderTemplate.DEEPSEEK
        baseUrl = profile?.baseUrl
            ?: AiProviderTemplate.DEEPSEEK.defaultBaseUrl.orEmpty()
        modelId = profile?.modelId
            ?: AiProviderTemplate.DEEPSEEK.defaultModelId.orEmpty()
        apiKey = ""
        apiKeyVisible = false
        temperature = profile?.temperature?.toString() ?: "0.2"
        editorConnectionResult = null
        pendingConnectionTest.value = null
        editorOpen = true
    }

    fun closeEditor(force: Boolean = false) {
        if (profileMutationInFlight && !force) return
        editorOpen = false
        editingProfile = null
        apiKey = ""
        apiKeyVisible = false
        editorConnectionResult = null
        pendingConnectionTest.value = null
    }

    LaunchedEffect(Unit) {
        viewModel.profileActionEvents.collect { event ->
            if (event.action == ProfileActionResult.CREATED ||
                event.action == ProfileActionResult.UPDATED
            ) {
                closeEditor(force = true)
            }
            val messageRes = when (event.failure) {
                ProfileMutationResult.ReplacementCredentialRequired ->
                    R.string.settings_profile_replacement_key_required
                else -> event.action.settingsMessageRes()
            }
            snackbarHostState.showSnackbar(context.getString(messageRes))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.connectionTestEvents.collect { event ->
            val pending = pendingConnectionTest.value
            if (pending?.requestId == event.requestId && pending.profileId == event.profileId) {
                editorConnectionResult = event.result
            }
        }
    }

    val normalizedTemperature = temperature.trim().toDoubleOrNull()
    val identityChanged = editingProfile?.let { profile ->
        profile.providerTemplate != providerTemplate ||
            profile.baseUrl.trim() != baseUrl.trim() ||
            profile.modelId.trim() != modelId.trim()
    } ?: false
    val canTestSavedProfile = editingProfile != null && !identityChanged && apiKey.isBlank()
    val validation = AiProfileValidation.validate(
        displayName, baseUrl, modelId, apiKey, temperature, canTestSavedProfile
    )
    val verifiedModels = (editorConnectionResult as? AiClientResult.Success)
        ?.availableModelIds.orEmpty()
    val testing = draftTestInFlight || editingProfile?.profileId in inFlightIds
    val saveEnabled = validation.valid && modelId.trim() in verifiedModels &&
        !profileMutationInFlight && !testing

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
                title = { Text(stringResource(R.string.settings_ai_profile_title)) },
                actions = {
                    IconButton(
                        onClick = { openEditor(null) },
                        modifier = Modifier.testTag("profile_add_top")
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.settings_profile_add)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
    ) { contentPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(contentPadding)
        ) {
            ProfileListContent(
                profiles = profiles,
                activeProfileId = activeProfileId,
                onAdd = { openEditor(null) },
                onSelect = viewModel::selectProfile,
                onManage = { actionProfile = it },
                onClearCache = { showClearCache = true },
                onDeleteAll = { showDeleteAll = true }
            )

            if (editorOpen) {
                val editor: @Composable () -> Unit = {
                    ProfileEditor(
                        editing = editingProfile != null,
                        displayName = displayName,
                        onDisplayNameChange = { displayName = it },
                        providerTemplate = providerTemplate,
                        onProviderChange = { template ->
                            providerTemplate = template
                            baseUrl = template.defaultBaseUrl.orEmpty()
                            modelId = template.defaultModelId.orEmpty()
                            invalidateConnectionTestResult()
                        },
                        baseUrl = baseUrl,
                        onBaseUrlChange = {
                            baseUrl = it
                            invalidateConnectionTestResult()
                        },
                        modelId = modelId,
                        onModelIdChange = {
                            modelId = it
                            invalidateConnectionTestResult()
                        },
                        apiKey = apiKey,
                        onApiKeyChange = {
                            apiKey = it
                            invalidateConnectionTestResult()
                        },
                        apiKeyVisible = apiKeyVisible,
                        onToggleApiKeyVisibility = { apiKeyVisible = !apiKeyVisible },
                        temperature = temperature,
                        onTemperatureChange = {
                            temperature = it
                            invalidateConnectionTestResult()
                        },
                        identityChanged = identityChanged,
                        testing = testing,
                        connectionResult = editorConnectionResult,
                        validation = validation,
                        validationAttempted = validationAttempted,
                        onValidate = {
                            validationAttempted = true
                            validation.valid
                        },
                        saveEnabled = saveEnabled,
                        mutationInFlight = profileMutationInFlight,
                        onTest = {
                            editorConnectionResult = null
                            val previousPending = pendingConnectionTest.value
                            val requestId = UUID.randomUUID().toString()
                            val profileId = if (canTestSavedProfile) {
                                requireNotNull(editingProfile).profileId
                            } else AI_DRAFT_CONNECTION_TEST_ID
                            pendingConnectionTest.value = PendingConnectionTest(
                                requestId = requestId,
                                profileId = profileId
                            )
                            if (canTestSavedProfile) {
                                editingProfile?.let {
                                    viewModel.testConnection(
                                        it.profileId, requestId, normalizedTemperature
                                    )
                                }
                            } else {
                                val accepted = viewModel.testConnectionDraft(
                                    requestId = requestId,
                                    providerTemplate = providerTemplate,
                                    baseUrl = baseUrl,
                                    modelId = modelId,
                                    apiKey = apiKey,
                                    temperatureInput = temperature
                                )
                                if (!accepted) {
                                    pendingConnectionTest.value = previousPending
                                }
                            }
                        },
                        onSave = {
                            val value = normalizedTemperature
                            if (value != null) {
                                editingProfile?.let { profile ->
                                    viewModel.updateProfile(
                                        profileId = profile.profileId,
                                        displayName = displayName,
                                        providerTemplate = providerTemplate,
                                        baseUrl = baseUrl,
                                        modelId = modelId,
                                        replacementApiKey = apiKey,
                                        temperatureInput = temperature
                                    )
                                } ?: viewModel.createProfile(
                                    displayName = displayName,
                                    providerTemplate = providerTemplate,
                                    baseUrl = baseUrl,
                                    modelId = modelId,
                                    apiKey = apiKey,
                                    temperature = value
                                )
                            }
                        },
                        onDismiss = { closeEditor() }
                    )
                }
                Dialog(
                    onDismissRequest = { closeEditor() },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f)
                            .fillMaxHeight(0.9f).imePadding(),
                        shape = ProfileEditorShape,
                        color = MaterialTheme.colorScheme.background
                    ) { editor() }
                }
            }
        }
    }

    actionProfile?.let { profile ->
        ModalBottomSheet(onDismissRequest = { actionProfile = null }) {
            ProfileSheetAction(
                icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                label = stringResource(R.string.settings_profile_edit),
                onClick = {
                    actionProfile = null
                    openEditor(profile)
                }
            )
            HorizontalDivider()
            ProfileSheetAction(
                icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                label = stringResource(R.string.settings_profile_delete),
                destructive = true,
                onClick = {
                    actionProfile = null
                    deleteProfile = profile
                }
            )
            Spacer(Modifier.height(10.dp))
            ProfileSheetAction(
                icon = { Icon(Icons.Outlined.Close, contentDescription = null) },
                label = stringResource(R.string.settings_profile_action_cancel),
                onClick = { actionProfile = null }
            )
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    deleteProfile?.let { profile ->
        DestructiveConfirmation(
            title = stringResource(R.string.settings_profile_delete_title),
            message = stringResource(R.string.settings_profile_delete_message, profile.displayName),
            confirmLabel = stringResource(R.string.settings_profile_delete_confirm),
            onDismiss = { deleteProfile = null },
            onConfirm = {
                deleteProfile = null
                viewModel.deleteProfile(profile.profileId)
            }
        )
    }
    if (showClearCache) {
        DestructiveConfirmation(
            title = stringResource(R.string.settings_ai_cache_clear_title),
            message = stringResource(R.string.settings_ai_cache_clear_message),
            confirmLabel = stringResource(R.string.settings_ai_cache_clear_confirm),
            onDismiss = { showClearCache = false },
            onConfirm = {
                showClearCache = false
                viewModel.clearAiExplanationCache()
            }
        )
    }
    if (showDeleteAll) {
        DestructiveConfirmation(
            title = stringResource(R.string.settings_profiles_delete_all_title),
            message = stringResource(R.string.settings_profiles_delete_all_message),
            confirmLabel = stringResource(R.string.settings_profiles_delete_all_confirm),
            onDismiss = { showDeleteAll = false },
            onConfirm = {
                showDeleteAll = false
                viewModel.deleteAllProfiles()
            }
        )
    }
}

@Composable
private fun ProfileListContent(
    profiles: List<AiProviderProfile>,
    activeProfileId: String?,
    onAdd: () -> Unit,
    onSelect: (String) -> Unit,
    onManage: (AiProviderProfile) -> Unit,
    onClearCache: () -> Unit,
    onDeleteAll: () -> Unit
) {
    if (profiles.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier.size(88.dp).background(
                        settingsAccentColor().copy(alpha = 0.1f),
                        CircleShape
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = settingsAccentColor()
                    )
                }
                Text(
                    stringResource(R.string.settings_profiles_empty),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.settings_profiles_empty_supporting),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onAdd,
                    modifier = Modifier.fillMaxWidth().testTag("profile_add_empty"),
                    shape = ProfileControlShape,
                    colors = ButtonDefaults.buttonColors(containerColor = settingsAccentColor())
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.settings_profile_add))
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(profiles, key = { it.profileId }) { profile ->
            ProfileCard(
                profile = profile,
                active = profile.profileId == activeProfileId,
                onSelect = { onSelect(profile.profileId) },
                onManage = { onManage(profile) }
            )
        }
        item {
            Spacer(Modifier.height(20.dp))
            GlobalProfileAction(
                label = stringResource(R.string.settings_ai_cache_clear),
                onClick = onClearCache
            )
            Spacer(Modifier.height(10.dp))
            GlobalProfileAction(
                label = stringResource(R.string.settings_profiles_delete_all),
                onClick = onDeleteAll
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProfileCard(
    profile: AiProviderProfile,
    active: Boolean,
    onSelect: () -> Unit,
    onManage: () -> Unit
) {
    Card(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()
            .testTag("profile_card_${profile.profileId}"),
        onClick = onSelect,
        shape = ProfileCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(
            width = if (active) 1.5.dp else 0.5.dp,
            color = if (active) settingsAccentColor()
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(34.dp).background(
                    providerColor(profile.providerTemplate).copy(alpha = 0.22f),
                    CircleShape
                )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(
                        R.string.settings_profile_card_summary,
                        profileTemplateLabel(profile.providerTemplate),
                        profile.modelId
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onManage,
                modifier = Modifier.testTag("profile_manage_${profile.profileId}")
            ) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.settings_profile_manage)
                )
            }
            if (active) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.settings_profile_active),
                    tint = settingsAccentColor()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditor(
    editing: Boolean,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    providerTemplate: AiProviderTemplate,
    onProviderChange: (AiProviderTemplate) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    modelId: String,
    onModelIdChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onToggleApiKeyVisibility: () -> Unit,
    temperature: String,
    onTemperatureChange: (String) -> Unit,
    identityChanged: Boolean,
    testing: Boolean,
    connectionResult: AiClientResult?,
    validation: AiProfileValidation,
    validationAttempted: Boolean,
    onValidate: () -> Boolean,
    saveEnabled: Boolean,
    mutationInFlight: Boolean,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val remoteError = (connectionResult as? AiClientResult.Failure)?.error
    val models = (connectionResult as? AiClientResult.Success)?.availableModelIds.orEmpty()
    val verified = modelId.trim() in models
    val successColor = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFF137D47)
    } else Color(0xFF30D580)
    val nameError = if (validationAttempted && validation.nameRequired) {
        R.string.settings_profile_name_required
    } else null
    val endpointError = when {
        validationAttempted && validation.endpointInvalid -> R.string.settings_profile_endpoint_invalid
        remoteError == AiError.InvalidEndpoint -> R.string.settings_profile_endpoint_invalid
        remoteError is AiError.HttpNotFound -> R.string.settings_ai_error_not_found
        else -> null
    }
    val modelError = when {
        validationAttempted && validation.modelRequired -> R.string.settings_profile_model_required
        remoteError == AiError.ModelUnavailable -> R.string.ai_error_model_unavailable
        else -> null
    }
    val keyError = when {
        validationAttempted && validation.keyRequired -> R.string.settings_profile_key_required
        remoteError == AiError.CredentialMissing -> R.string.settings_ai_error_credential_missing
        remoteError is AiError.HttpAuth -> R.string.settings_profile_key_rejected
        else -> null
    }
    val temperatureError = if (validationAttempted && validation.temperatureInvalid) {
        R.string.settings_profile_temperature_invalid
    } else null
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .imePadding().padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    if (editing) R.string.settings_profile_edit_title
                    else R.string.settings_profile_add_title
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = onDismiss, enabled = !mutationInFlight) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.settings_profile_action_cancel)
                )
            }
        }
        Text(
            stringResource(R.string.settings_profile_editor_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ProfileSectionLabel(R.string.settings_profile_provider_section)
        var providerMenuExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = providerMenuExpanded,
            onExpandedChange = {
                if (!mutationInFlight) providerMenuExpanded = !providerMenuExpanded
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            TextField(
                value = profileTemplateLabel(providerTemplate),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.settings_profile_template)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded)
                },
                enabled = !mutationInFlight,
                modifier = Modifier.fillMaxWidth()
                    .menuAnchor()
                    .testTag("profile_provider_selector"),
                shape = ProfileControlShape,
                colors = profileFieldColors()
            )
            ExposedDropdownMenu(
                expanded = providerMenuExpanded,
                onDismissRequest = { providerMenuExpanded = false }
            ) {
                AiProviderTemplate.entries.forEach { template ->
                    DropdownMenuItem(
                        text = { Text(profileTemplateLabel(template)) },
                        onClick = {
                            if (mutationInFlight) return@DropdownMenuItem
                            providerMenuExpanded = false
                            onProviderChange(template)
                        },
                        leadingIcon = if (providerTemplate == template) {
                            {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    modifier = Modifier.testTag(
                                        "profile_provider_selected_${template.name}"
                                    )
                                )
                            }
                        } else null,
                        modifier = Modifier.testTag("profile_provider_option_${template.name}")
                    )
                }
            }
        }
        ProfileInputField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            label = R.string.settings_profile_name,
            tag = "profile_display_name",
            enabled = !mutationInFlight,
            error = nameError
        )
        ProfileSectionLabel(R.string.settings_profile_connection_section)
        Surface(shape = ProfileCardShape, color = profileFieldBackground()) {
            Column {
                ProfileInputField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = R.string.settings_profile_base_url,
                    tag = "profile_base_url",
                    enabled = !mutationInFlight,
                    error = endpointError,
                    keyboardType = KeyboardType.Uri
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                ProfileInputField(
                    value = modelId,
                    onValueChange = onModelIdChange,
                    label = R.string.settings_profile_model,
                    tag = "profile_model_id",
                    enabled = !mutationInFlight,
                    error = modelError,
                    trailingIcon = if (verified) {
                        {
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(
                                    onClick = { expanded = true },
                                    enabled = !mutationInFlight && !testing,
                                    modifier = Modifier.testTag("profile_model_dropdown")
                                ) {
                                    Icon(
                                        Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.settings_profile_models_show),
                                        tint = successColor
                                    )
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                    modifier = Modifier.heightIn(max = 280.dp)
                                ) {
                                    models.forEach { id ->
                                        DropdownMenuItem(
                                            text = { Text(id) },
                                            onClick = {
                                                expanded = false
                                                if (id != modelId.trim()) onModelIdChange(id)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } else null
                )
                if (verified) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("profile_model_verified"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = successColor)
                        Text(
                            stringResource(R.string.settings_profile_models_verified),
                            style = MaterialTheme.typography.bodySmall,
                            color = successColor
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                ProfileInputField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = R.string.settings_api_key,
                    tag = "profile_api_key",
                    enabled = !mutationInFlight,
                    error = keyError,
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = onToggleApiKeyVisibility,
                            enabled = !mutationInFlight,
                            modifier = Modifier.testTag(
                                if (apiKeyVisible) "profile_api_key_visible" else "profile_api_key_hidden"
                            )
                        ) {
                            Icon(
                                if (apiKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(
                                    if (apiKeyVisible) R.string.settings_api_key_hide
                                    else R.string.settings_api_key_show
                                )
                            )
                        }
                    }
                )
            }
        }
        if (editing) {
            Text(
                stringResource(
                    if (identityChanged) R.string.settings_profile_key_identity_changed_hint
                    else R.string.settings_profile_key_keep_hint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ProfileSectionLabel(R.string.settings_profile_parameters_section)
        ProfileInputField(
            value = temperature,
            onValueChange = onTemperatureChange,
            label = R.string.settings_profile_temperature,
            tag = "profile_temperature",
            enabled = !mutationInFlight,
            error = temperatureError,
            keyboardType = KeyboardType.Decimal
        )
        Text(
            stringResource(R.string.settings_profile_temperature_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AiProfileDisclosure()
        ProfileConnectionTestControl(
            enabled = !mutationInFlight,
            testing = testing,
            onConfirmed = onTest,
            onValidate = onValidate,
            modifier = Modifier.fillMaxWidth().testTag("profile_editor_test_connection")
        )
        connectionResult?.let { result ->
            val message = when (result) {
                is AiClientResult.Success -> stringResource(
                    if (verified) R.string.settings_ai_connection_test_success
                    else R.string.ai_error_model_unavailable
                )
                is AiClientResult.Failure -> result.error.toUiMessage().let { mapped ->
                    stringResource(mapped.resourceId, *mapped.formatArgs.toTypedArray())
                }
            }
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().testTag("profile_editor_test_result")
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = if (verified) {
                    successColor
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Button(
            onClick = onSave,
            enabled = saveEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("profile_editor_save"),
            shape = ProfileControlShape,
            colors = ButtonDefaults.buttonColors(containerColor = settingsAccentColor())
        ) { Text(stringResource(R.string.settings_profile_save)) }
    }
}

@Composable
private fun ProfileSectionLabel(label: Int) {
    Text(
        stringResource(label),
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun profileFieldBackground() = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

@Composable
private fun profileFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = profileFieldBackground(),
    unfocusedContainerColor = profileFieldBackground(),
    disabledContainerColor = profileFieldBackground(),
    errorContainerColor = profileFieldBackground(),
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    cursorColor = settingsAccentColor(),
    focusedLabelColor = settingsAccentColor()
)

@Composable
private fun ProfileInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    tag: String,
    enabled: Boolean,
    error: Int?,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    Column {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(label)) },
            modifier = Modifier.fillMaxWidth().testTag(tag).then(
                if (error != null) Modifier.border(1.dp, MaterialTheme.colorScheme.error, ProfileControlShape)
                else Modifier
            ),
            enabled = enabled,
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            shape = ProfileControlShape,
            colors = profileFieldColors()
        )
        if (error != null) {
            Text(
                stringResource(error),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("${tag}_error").semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
internal fun AiProfileDisclosure() {
    Text(
        stringResource(R.string.settings_ai_third_party_disclosure),
        modifier = Modifier.testTag("settings_ai_disclosure"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun ProfileConnectionTestControl(
    enabled: Boolean,
    testing: Boolean,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    onValidate: () -> Boolean = { true }
) {
    var showConfirmation by remember { mutableStateOf(false) }

    Button(
        onClick = { if (onValidate()) showConfirmation = true },
        enabled = enabled && !testing,
        modifier = modifier.heightIn(min = 48.dp),
        shape = ProfileControlShape,
        colors = ButtonDefaults.buttonColors(containerColor = settingsAccentColor())
    ) {
        if (testing) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
        }
        Text(
            stringResource(
                if (testing) R.string.settings_ai_connection_testing
                else R.string.settings_ai_connection_test
            )
        )
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text(stringResource(R.string.settings_ai_connection_test_title)) },
            text = { Text(stringResource(R.string.settings_ai_connection_test_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmation = false
                    if (enabled && !testing && onValidate()) onConfirmed()
                }) {
                    Text(stringResource(R.string.settings_ai_connection_test_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) {
                    Text(stringResource(R.string.settings_ai_connection_test_cancel))
                }
            }
        )
    }
}

@Composable
private fun ProfileSheetAction(
    icon: @Composable () -> Unit,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            label,
            color = if (destructive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun GlobalProfileAction(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().clickable(onClick = onClick),
        shape = ProfileControlShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.heightIn(min = 54.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Text(label, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DestructiveConfirmation(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_profile_action_cancel))
            }
        }
    )
}

@Composable
private fun profileTemplateLabel(template: AiProviderTemplate): String = stringResource(
    when (template) {
        AiProviderTemplate.DEEPSEEK -> R.string.settings_provider_deepseek
        AiProviderTemplate.KIMI -> R.string.settings_provider_kimi
        AiProviderTemplate.ZHIPU -> R.string.settings_provider_zhipu
        AiProviderTemplate.OPENAI_COMPATIBLE -> R.string.settings_provider_openai_compatible
    }
)

private fun providerColor(template: AiProviderTemplate): Color = when (template) {
    AiProviderTemplate.DEEPSEEK -> Color(0xFF5B8FF9)
    AiProviderTemplate.KIMI -> Color(0xFF5AD08B)
    AiProviderTemplate.ZHIPU -> Color(0xFFB56BEA)
    AiProviderTemplate.OPENAI_COMPATIBLE -> Color(0xFFFFC857)
}
