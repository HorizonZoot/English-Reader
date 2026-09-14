package io.github.zoot.englishreader.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnglishReaderDatabaseSchemaAndroidTest {

    private lateinit var database: EnglishReaderDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EnglishReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun databaseVersionAndExplanationCacheColumns_remainStable() {
        val sqlite = database.openHelper.readableDatabase

        // 版本号在这里硬编码是**故意的**：它让任何 schema 变更都必须显式改这一行，
        // 从而迫使改动者回答「迁移写了吗、导出的 schema 更新了吗、迁移测试加了吗」。
        // v5 增加字符位置表，由 MIGRATION_4_5 和对应 MigrationTestHelper 用例覆盖。
        assertEquals(5, sqlite.version)

        val columns = sqlite.query("PRAGMA table_info(explanation_cache)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.getString(nameColumn))
                }
            }
        }

        assertEquals(
            setOf("cacheKey", "explanation", "createdAt"),
            columns
        )
    }
}
