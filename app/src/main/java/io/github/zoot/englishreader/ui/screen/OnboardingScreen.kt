package io.github.zoot.englishreader.ui.screen

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.viewmodel.OnboardingEvent
import io.github.zoot.englishreader.viewmodel.OnboardingState
import io.github.zoot.englishreader.viewmodel.OnboardingViewModel

/** Normal navigation (including its update ViewModel) is not composed until startup is resolved. */
@Composable
fun OnboardingGate(
    viewModel: OnboardingViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                val message = when (event) {
                    OnboardingEvent.READ_FAILED -> R.string.onboarding_read_failed
                    OnboardingEvent.SAVE_FAILED -> R.string.onboarding_save_failed
                }
                snackbarHostState.showSnackbar(context.getString(message))
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            OnboardingState.Loading -> Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.onboarding_loading), Modifier.padding(16.dp))
            }
            is OnboardingState.Guide -> OnboardingScreen(
                onFinish = viewModel::finish,
                saving = current.saving
            )
            OnboardingState.Ready -> content()
        }
        // Kept outside the gate so a failed write remains visible after entering the app.
        SnackbarHost(
            snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
        )
    }
}

private enum class OnboardingPage(@StringRes val title: Int, @StringRes val body: Int, @StringRes val image: Int) {
    LIBRARY(R.string.onboarding_library_title, R.string.onboarding_library_body, R.string.onboarding_library_image),
    DICTIONARY(R.string.onboarding_dictionary_title, R.string.onboarding_dictionary_body, R.string.onboarding_dictionary_image),
    VOCABULARY(R.string.onboarding_vocabulary_title, R.string.onboarding_vocabulary_body, R.string.onboarding_vocabulary_image),
    TTS(R.string.onboarding_tts_title, R.string.onboarding_tts_body, R.string.onboarding_tts_image),
    AI(R.string.onboarding_ai_title, R.string.onboarding_ai_body, R.string.onboarding_ai_image)
}

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    saving: Boolean = false,
    replay: Boolean = false
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val pages = OnboardingPage.entries
    val page = pages[pageIndex]
    // Consume Back while saving too: a second Back must not finish the Activity mid-write.
    BackHandler { if (!saving) onFinish() }

    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.onboarding_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                TextButton(onClick = onFinish, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }
            Text(
                stringResource(R.string.onboarding_page_position, pageIndex + 1, pages.size),
                modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            key(page) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    OnboardingIllustration(page)
                    Text(
                        stringResource(page.title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        stringResource(page.body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { if (pageIndex > 0) pageIndex-- },
                    enabled = pageIndex > 0 && !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.onboarding_previous)) }
                Button(
                    onClick = { if (pageIndex < pages.lastIndex) pageIndex++ else onFinish() },
                    enabled = !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text(stringResource(when {
                        saving -> R.string.onboarding_saving
                        pageIndex < pages.lastIndex -> R.string.onboarding_next
                        replay -> R.string.onboarding_return_settings
                        else -> R.string.onboarding_start
                    }))
                }
            }
        }
    }
}

@Composable
private fun OnboardingIllustration(page: OnboardingPage) {
    val description = stringResource(page.image)
    Card(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = description
            role = Role.Image
        },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (page) {
                OnboardingPage.LIBRARY -> {
                    IllustrationRow(Icons.Outlined.Search, R.string.library_search_hint)
                    IllustrationRow(Icons.AutoMirrored.Outlined.MenuBook, R.string.onboarding_sample_book)
                    IllustrationRow(Icons.Outlined.Add, R.string.import_article)
                }
                OnboardingPage.DICTIONARY -> {
                    Text(stringResource(R.string.onboarding_sample_word), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    IllustrationRow(Icons.Outlined.TouchApp, R.string.onboarding_hold_release)
                    IllustrationRow(Icons.AutoMirrored.Outlined.MenuBook, R.string.onboarding_sample_definition)
                }
                OnboardingPage.VOCABULARY -> {
                    IllustrationRow(Icons.Outlined.BookmarkBorder, R.string.onboarding_sample_word)
                    IllustrationRow(Icons.Outlined.Add, R.string.add_to_vocabulary)
                    IllustrationRow(Icons.AutoMirrored.Outlined.MenuBook, R.string.nav_vocabulary)
                }
                OnboardingPage.TTS -> {
                    IllustrationRow(Icons.Outlined.VolumeUp, R.string.reading_sentence_play)
                    Row(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(12, 24, 36, 20, 40, 28, 16).forEach { height ->
                            Box(Modifier.weight(1f).height(height.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
                        }
                    }
                    IllustrationRow(Icons.Outlined.Settings, R.string.reading_voice_settings)
                }
                OnboardingPage.AI -> {
                    IllustrationRow(Icons.Outlined.AutoAwesome, R.string.onboarding_ai_sample)
                    IllustrationRow(Icons.Outlined.Cloud, R.string.onboarding_ai_provider)
                    IllustrationRow(Icons.Outlined.Lock, R.string.onboarding_ai_optional)
                }
            }
        }
    }
}

@Composable
private fun IllustrationRow(icon: ImageVector, @StringRes text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}
