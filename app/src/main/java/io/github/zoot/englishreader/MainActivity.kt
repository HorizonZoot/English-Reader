package io.github.zoot.englishreader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import io.github.zoot.englishreader.data.dictionary.DictionaryPackInstaller
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.model.TtsSystemAction
import io.github.zoot.englishreader.ui.screen.ArticleListScreen
import io.github.zoot.englishreader.ui.screen.AiProfileConfigScreen
import io.github.zoot.englishreader.ui.screen.BookTocScreen
import io.github.zoot.englishreader.ui.screen.ReadingScreen
import io.github.zoot.englishreader.ui.screen.SettingsScreen
import io.github.zoot.englishreader.ui.screen.SettingsCacheManagementScreen
import io.github.zoot.englishreader.ui.screen.VocabularyScreen
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import io.github.zoot.englishreader.util.TestDataGenerator
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.viewmodel.SettingsViewModel
import io.github.zoot.englishreader.viewmodel.ReadingViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

internal suspend fun installContentAfterInitialTheme(
    themeOptions: Flow<ThemeOption>,
    installContent: (ThemeOption) -> Unit
) {
    val initialTheme = try {
        themeOptions.first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ThemeOption.DEFAULT
    }

    currentCoroutineContext().ensureActive()
    installContent(initialTheme)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var articleRepository: ArticleRepository

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    @Inject
    lateinit var dictionaryPackInstaller: DictionaryPackInstaller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 词库状态在启动时刷新一次，确保清除应用数据后 UI 不显示过期的「已安装」。
        // 只读数据库条数，无网络、无耗时操作。
        lifecycleScope.launch {
            try {
                dictionaryPackInstaller.refreshState()
            } catch (e: Exception) {
                // 刷新失败不阻塞启动，下次打开设置页会再刷新
                Log.w("MainActivity", "Dictionary pack state refresh failed", e)
            }
        }

        // 按版本号刷新内置样本数据：版本落后时原子重灌（解绑生词→删旧样本→插新样本），
        // 只影响样本 source 白名单内的文章，用户导入的文章及生词均保留。
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val installedVersion = prefs.getInt("sample_data_version", 0)
        if (installedVersion < TestDataGenerator.SAMPLE_DATA_VERSION) {
            lifecycleScope.launch {
                // 刷新失败不写版本号，下次启动重试；捕获异常避免 onCreate 崩溃导致启动循环
                try {
                    articleRepository.refreshSampleArticles(
                        sampleSources = TestDataGenerator.SAMPLE_SOURCES,
                        samples = TestDataGenerator.getSampleArticles()
                    )
                    prefs.edit().putInt("sample_data_version", TestDataGenerator.SAMPLE_DATA_VERSION).apply()
                } catch (cancellation: CancellationException) {
                    // refreshSampleArticles 是 suspend，Activity 在刷新期间销毁（首启即旋转/退出）
                    // 会让它抛 CancellationException。必须原样上抛：被通用 catch 吞掉的话，
                    // 协程会以「正常完成」收尾而非「已取消」，而且每次正常取消都会打出一条
                    // 声称刷新失败的 ERROR 日志。本文件 installContentAfterInitialTheme 用的
                    // 就是这个成对写法。
                    throw cancellation
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to refresh sample articles", e)
                }
            }
        }

        lifecycleScope.launch {
            installContentAfterInitialTheme(settingsPreferences.themeOption) { initialTheme ->
                setContent {
                    val themeOption by settingsPreferences.themeOption
                        .collectAsStateWithLifecycle(initialValue = initialTheme)
                    val darkTheme = when (themeOption) {
                        ThemeOption.SYSTEM -> isSystemInDarkTheme()
                        ThemeOption.LIGHT -> false
                        ThemeOption.DARK -> true
                    }
                    SideEffect {
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !darkTheme
                            isAppearanceLightNavigationBars = !darkTheme
                        }
                    }
                    EnglishReaderTheme(darkTheme = darkTheme) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            EnglishReaderNavigation()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EnglishReaderNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        // 各目的地页面自行负责顶部应用栏和系统栏 insets。外层外壳只预留导航栏的位置；
        // 在这里再套一层默认的系统栏 insets，会让每个内嵌页面被二次下移。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (currentRoute == "article_list" || currentRoute == "vocabulary" || currentRoute == "settings") {
                ArticleUiTheme {
                    val navigationColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                            shape = RoundedCornerShape(26.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            shadowElevation = 2.dp
                        ) {
                            NavigationBar(
                                containerColor = Color.Transparent,
                                tonalElevation = 0.dp,
                                windowInsets = WindowInsets(0, 0, 0, 0)
                            ) {
                                NavigationBarItem(
                                    icon = {
                                        Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null)
                                    },
                                    label = { Text(stringResource(R.string.nav_articles)) },
                                    colors = navigationColors,
                                    selected = currentRoute == "article_list",
                                    onClick = {
                                        if (currentRoute != "article_list") {
                                            navController.navigate("article_list") {
                                                // 让文章列表留在返回栈上，保证返回导航行为正确
                                                popUpTo("article_list") { inclusive = false }
                                            }
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Outlined.BookmarkBorder, contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_vocabulary)) },
                                    colors = navigationColors,
                                    selected = currentRoute == "vocabulary",
                                    onClick = {
                                        if (currentRoute != "vocabulary") {
                                            navController.navigate("vocabulary") {
                                                popUpTo("article_list")
                                            }
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_settings)) },
                                    colors = navigationColors,
                                    selected = currentRoute == "settings",
                                    onClick = {
                                        if (currentRoute != "settings") {
                                            navController.navigate("settings") {
                                                popUpTo("article_list")
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "article_list",
            modifier = Modifier.padding(padding)
        ) {
            composable("article_list") {
            ArticleListScreen(
                onArticleClick = { articleId ->
                    navController.navigate("reading/$articleId")
                },
                onBookClick = { bookId ->
                    navController.navigate("book/$bookId")
                }
            )
        }

        composable(
            route = "reading/{articleId}",
            arguments = listOf(navArgument("articleId") { type = NavType.LongType })
        ) { backStackEntry ->
            val articleId = backStackEntry.arguments?.getLong("articleId") ?: 0L
            val viewModel: ReadingViewModel = hiltViewModel()
            TtsSystemActions(viewModel.ttsSystemActions)
            ReadingScreen(
                articleId = articleId,
                onBack = { navController.popBackStack() },
                // 从章节跳目录用 navigate 而非 popBackStack：用户可能是从书架直接进的某一章
                // （继续阅读），此时返回栈里没有目录页可弹。
                onOpenToc = { bookId -> navController.navigate("book/$bookId") },
                viewModel = viewModel
            )
        }

        composable(
            route = "book/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) {
            BookTocScreen(
                onBack = { navController.popBackStack() },
                onChapterClick = { articleId ->
                    navController.navigate("reading/$articleId")
                }
            )
        }

        composable("vocabulary") {
            VocabularyScreen()
        }

        composable("settings") {
            val viewModel: SettingsViewModel = hiltViewModel()
            TtsSystemActions(viewModel.ttsSystemActions)
            SettingsScreen(
                onOpenAiProfile = { navController.navigate("settings/ai-profile") },
                onOpenCacheManagement = { navController.navigate("settings/cache") },
                viewModel = viewModel
            )
        }

        composable("settings/cache") {
            SettingsCacheManagementScreen(onBack = { navController.popBackStack() })
        }

        composable("settings/ai-profile") {
            AiProfileConfigScreen(
                onBack = { navController.popBackStack() }
            )
        }
        }
    }
}

@Composable
private fun TtsSystemActions(actions: Flow<TtsSystemAction>) {
    val context = LocalContext.current
    LaunchedEffect(actions) {
        actions.collect { action ->
            if (!openTtsSystemAction(context, action)) {
                Toast.makeText(context, R.string.tts_system_action_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun openTtsSystemAction(context: Context, action: TtsSystemAction): Boolean {
    val intent = Intent(when (action) {
        // Android exposes this settings action at runtime, but not as an SDK 34 constant.
        TtsSystemAction.OPEN_SETTINGS -> "com.android.settings.TTS_SETTINGS"
        TtsSystemAction.INSTALL_DATA -> TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
    })
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
