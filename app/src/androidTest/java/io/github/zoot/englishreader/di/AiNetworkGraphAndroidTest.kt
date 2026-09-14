package io.github.zoot.englishreader.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zoot.englishreader.data.ai.AiClient
import io.github.zoot.englishreader.data.repository.AiExplanationRepository
import io.github.zoot.englishreader.data.repository.AiExplanationOperationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AiNetworkGraphAndroidTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var aiClient: AiClient

    @Inject
    lateinit var aiExplanationRepository: AiExplanationRepository

    @Inject
    lateinit var aiExplanationOperationRegistry: AiExplanationOperationRegistry

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun minimalSettingsGraph_resolvesAiClient() {
        assertNotNull(aiClient)
        assertNotNull(aiExplanationRepository)
        assertNotNull(aiExplanationOperationRegistry)
        assertTrue(aiClient.inFlightProfileIds.value.isEmpty())
    }
}
