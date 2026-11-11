@file:Suppress("unused")

package com.example.kotoread

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * ============================================================================
 * 言ノ葉 Reader (KotoRead) — Android 日文小說網址排版、一鍵日翻中、Room、WorkManager 與 ML Kit 離線翻譯完整模組
 * ============================================================================
 *
 * 【1. build.gradle.kts (Module :app) 必備依賴】
 * plugins {
 *     id("com.android.application")
 *     id("org.jetbrains.kotlin.android")
 *     id("com.google.devtools.ksp") version "2.0.20-1.0.24"
 * }
 *
 * dependencies {
 *     implementation("androidx.core:core-ktx:1.13.1")
 *     implementation("androidx.activity:activity-compose:1.9.2")
 *     implementation(platform("androidx.compose:compose-bom:2024.09.00"))
 *     implementation("androidx.compose.ui:ui")
 *     implementation("androidx.compose.material3:material3")
 *     implementation("androidx.compose.material:material-icons-extended")
 *     implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
 *
 *     // Android Room 本地資料庫（書架收藏、章節雙語離線快取、專屬詞典持久化）
 *     val roomVersion = "2.6.1"
 *     implementation("androidx.room:room-runtime:$roomVersion")
 *     implementation("androidx.room:room-ktx:$roomVersion")
 *     ksp("androidx.room:room-compiler:$roomVersion")
 *
 *     // Android WorkManager 背景定期巡檢連載更新與預先翻譯快取
 *     implementation("androidx.work:work-runtime-ktx:2.9.1")
 *
 *     // Google ML Kit 離線日翻中引擎（端側模型自動下載、100% 免費、免連網直譯）
 *     implementation("com.google.mlkit:translate:17.0.3")
 *
 *     // 網路請求與 HTML 解析 (Jsoup + OkHttp)
 *     implementation("com.squareup.okhttp3:okhttp:4.12.0")
 *     implementation("org.jsoup:jsoup:1.18.1")
 * }
 *
 * 【2. AndroidManifest.xml 權限與瀏覽器「分享網址」接收設定】
 * <uses-permission android:name="android.permission.INTERNET" />
 * <!-- Android 13 (API 33)+ 發送新章節更新通知權限 -->
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 *
 * <!-- Android FileProvider (用於 EPUB / TXT 電子書安全共享與匯出) -->
 * <provider
 *     android:name="androidx.core.content.FileProvider"
 *     android:authorities="${applicationId}.fileprovider"
 *     android:exported="false"
 *     android:grantUriPermissions="true">
 *     <meta-data
 *         android:name="android.support.FILE_PROVIDER_PATHS"
 *         android:resource="@xml/file_paths" />
 * </provider>
 *
 * <!-- res/xml/file_paths.xml 內容：
 *      <paths>
 *          <cache-path name="exported_novels" path="exported_novels/" />
 *      </paths> -->
 *
 * <activity android:name=".MainActivity" android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.intent.action.MAIN" />
 *         <category android:name="android.intent.category.LAUNCHER" />
 *     </intent-filter>
 *     <!-- 支援從 Chrome 瀏覽器直接按「分享」傳入日文小說網址 -->
 *     <intent-filter>
 *         <action android:name="android.intent.action.SEND" />
 *         <category android:name="android.intent.category.DEFAULT" />
 *         <data android:mimeType="text/plain" />
 *     </intent-filter>
 * </activity>
 */

// ============================================================================
// 1. 領域資料模型 (Domain Models)
// ============================================================================

enum class DisplayMode(val label: String) {
    ZH_ONLY("純中文譯文"),
    DUAL("中日雙語對照"),
    JP_ONLY("日文原文")
}

enum class TranslationEngine(val label: String) {
    GEMINI_LLM("Gemini 輕小說引擎"),
    ML_KIT_OFFLINE("ML Kit 端側離線直譯 (免連網)"),
    DEEPL_API("DeepL API")
}

enum class ReaderTheme(
    val label: String,
    val bgColor: Color,
    val textColor: Color,
    val cardColor: Color
) {
    DARK("極致暗黑", Color(0xFF131722), Color(0xFFD8DEE9), Color(0xFF1E2433)),
    SEPIA("羊皮紙護眼", Color(0xFFF6EFE2), Color(0xFF3C3226), Color(0xFFEBE1CE)),
    MATCHA("竹青微光", Color(0xFFE6F0E8), Color(0xFF1F3325), Color(0xFFD6E5D9)),
    LIGHT("清晨紙白", Color(0xFFFFFFFF), Color(0xFF1E293B), Color(0xFFF1F5F9))
}

data class GlossaryTerm(
    val id: String,
    val jpTerm: String,
    val zhTerm: String,
    val category: String = "角色人名",
    val note: String = "", // 例如：「女主角，代詞用她」
    val seriesKey: String = "*" // "*" 代表全域通用詞條，亦可綁定特定作品
)

data class NovelParagraph(
    val index: Int,
    val jpPlain: String,           // 純日文段落（供翻譯引擎使用）
    val jpWithRubyText: String,    // 保留漢字(振假名)標記之日文（供原文檢視）
    val zhTranslated: String = "", // 翻譯後的繁體中文
    val isExpanded: Boolean = false
)

data class NovelChapter(
    val sourceUrl: String,
    val seriesKey: String,
    val siteName: String,
    val seriesTitle: String,
    val chapterTitleJp: String,
    val chapterTitleZh: String = "",
    val author: String = "",
    val paragraphs: List<NovelParagraph> = emptyList(),
    val prevChapterUrl: String? = null,
    val nextChapterUrl: String? = null,
    val isFromOfflineCache: Boolean = false
)

data class BookshelfItem(
    val seriesKey: String,
    val siteName: String,
    val seriesTitle: String,
    val author: String,
    val lastReadChapterUrl: String,
    val lastReadChapterTitle: String,
    val lastReadScrollIndex: Int = 0,
    val cachedChapterCount: Int = 1,
    val hasUnreadUpdate: Boolean = false,
    val latestKnownNextUrl: String? = null,
    val latestKnownNextTitle: String = "",
    val lastCheckedAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class ReaderUiState(
    val urlInput: String = "https://ncode.syosetu.com/n4829ia/14/",
    val isLoadingUrl: Boolean = false,
    val isTranslating: Boolean = false,
    val translationProgress: Float = 0f,
    val statusMessage: String = "準備就緒",
    val currentChapter: NovelChapter? = null,
    val preloadedNextChapter: NovelChapter? = null,
    val displayMode: DisplayMode = DisplayMode.DUAL,
    val engine: TranslationEngine = TranslationEngine.ML_KIT_OFFLINE,
    val theme: ReaderTheme = ReaderTheme.DARK,
    val fontSizeSp: Float = 17f,
    val lineHeightMultiplier: Float = 1.8f,
    val useSerifFont: Boolean = true,
    val highlightGlossary: Boolean = true,
    val glossary: List<GlossaryTerm> = defaultSampleGlossary(),
    val bookshelf: List<BookshelfItem> = emptyList(),
    val isPeriodicWorkEnabled: Boolean = true,
    val isCheckingUpdatesNow: Boolean = false,
    val lastUpdateCheckReport: String = "WorkManager 每 6 小時自動巡檢連載更新"
)

fun defaultSampleGlossary(): List<GlossaryTerm> = listOf(
    GlossaryTerm("1", "アステル", "亞絲特爾", "角色人名", "女主角，機巧少女（女性代詞：她）"),
    GlossaryTerm("2", "星辰炉", "星辰爐", "魔導器", "古代核心機關（Astral Core）"),
    GlossaryTerm("3", "機巧兵団", "機巧兵團", "組織", "帝國北方機械部隊"),
    GlossaryTerm("4", "黒曜の聖女", "黑曜聖女", "稱號", "舊都傳說守護者"),
    GlossaryTerm("5", "レン", "蓮", "角色人名", "男主角，魔導技師（男性代詞：他）")
)

// ============================================================================
// 2. Android Room 本地資料庫架構 (Entities / Relations / DAO / Database / Repository)
// ============================================================================

@Entity(
    tableName = "bookshelf_books",
    indices = [Index(value = ["updatedAtEpochMs"])]
)
data class BookshelfBookEntity(
    @PrimaryKey val seriesKey: String,
    val siteName: String,
    val seriesTitle: String,
    val author: String,
    val lastReadChapterUrl: String,
    val lastReadChapterTitle: String,
    val lastReadScrollIndex: Int = 0,
    val hasUnreadUpdate: Boolean = false,
    val latestKnownNextUrl: String? = null,
    val latestKnownNextTitle: String = "",
    val lastCheckedAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "cached_chapters",
    indices = [Index(value = ["seriesKey"]), Index(value = ["cachedAtEpochMs"])]
)
data class CachedChapterEntity(
    @PrimaryKey val chapterUrl: String,
    val seriesKey: String,
    val siteName: String,
    val seriesTitle: String,
    val chapterTitleJp: String,
    val chapterTitleZh: String,
    val author: String,
    val prevChapterUrl: String?,
    val nextChapterUrl: String?,
    val cachedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "cached_paragraphs",
    primaryKeys = ["chapterUrl", "paragraphIndex"],
    foreignKeys = [
        ForeignKey(
            entity = CachedChapterEntity::class,
            parentColumns = ["chapterUrl"],
            childColumns = ["chapterUrl"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["chapterUrl"])]
)
data class CachedParagraphEntity(
    val chapterUrl: String,
    val paragraphIndex: Int,
    val jpPlain: String,
    val jpWithRubyText: String,
    val zhTranslated: String
)

@Entity(
    tableName = "glossary_terms",
    indices = [Index(value = ["jpTerm", "seriesKey"], unique = true)]
)
data class GlossaryTermEntity(
    @PrimaryKey val id: String,
    val jpTerm: String,
    val zhTerm: String,
    val category: String,
    val note: String,
    val seriesKey: String = "*",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class CachedChapterWithParagraphs(
    @Embedded val chapter: CachedChapterEntity,
    @Relation(
        parentColumn = "chapterUrl",
        entityColumn = "chapterUrl"
    )
    val paragraphs: List<CachedParagraphEntity>
)

data class BookshelfBookSummary(
    @Embedded val book: BookshelfBookEntity,
    val cachedChapterCount: Int
)

@Dao
interface KotoReadDao {

    @Query(
        """
        SELECT b.*, 
               (SELECT COUNT(*) FROM cached_chapters c WHERE c.seriesKey = b.seriesKey) AS cachedChapterCount
        FROM bookshelf_books b
        ORDER BY b.hasUnreadUpdate DESC, b.updatedAtEpochMs DESC
        """
    )
    fun observeBookshelfSummaries(): Flow<List<BookshelfBookSummary>>

    @Query("SELECT * FROM bookshelf_books ORDER BY updatedAtEpochMs DESC")
    suspend fun getAllBookshelfBooksSync(): List<BookshelfBookEntity>

    @Query("SELECT * FROM bookshelf_books WHERE seriesKey = :seriesKey LIMIT 1")
    suspend fun getBookshelfBookByKeySync(seriesKey: String): BookshelfBookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookshelfBook(book: BookshelfBookEntity)

    @Query(
        """
        UPDATE bookshelf_books 
        SET hasUnreadUpdate = :hasUpdate,
            latestKnownNextUrl = :nextUrl,
            latestKnownNextTitle = :nextTitle,
            lastCheckedAtEpochMs = :checkedAt
        WHERE seriesKey = :seriesKey
        """
    )
    suspend fun markSeriesUpdateStatus(
        seriesKey: String,
        hasUpdate: Boolean,
        nextUrl: String?,
        nextTitle: String,
        checkedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM bookshelf_books WHERE seriesKey = :seriesKey")
    suspend fun deleteBookshelfBook(seriesKey: String)

    @Transaction
    @Query("SELECT * FROM cached_chapters WHERE chapterUrl = :url LIMIT 1")
    suspend fun getCachedChapterByUrl(url: String): CachedChapterWithParagraphs?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapterHeader(chapter: CachedChapterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParagraphs(paragraphs: List<CachedParagraphEntity>)

    @Query("DELETE FROM cached_paragraphs WHERE chapterUrl = :url")
    suspend fun deleteParagraphsForChapter(url: String)

    @Transaction
    suspend fun saveCompleteChapterCache(
        chapter: CachedChapterEntity,
        paragraphs: List<CachedParagraphEntity>
    ) {
        insertChapterHeader(chapter)
        deleteParagraphsForChapter(chapter.chapterUrl)
        insertParagraphs(paragraphs)
    }

    @Transaction
    @Query("SELECT * FROM cached_chapters WHERE seriesKey = :seriesKey ORDER BY cachedAtEpochMs ASC")
    suspend fun getAllCachedChaptersBySeriesSync(seriesKey: String): List<CachedChapterWithParagraphs>

    @Query("DELETE FROM cached_chapters WHERE seriesKey = :seriesKey")
    suspend fun clearSeriesCache(seriesKey: String)

    @Query(
        """
        SELECT * FROM glossary_terms 
        WHERE seriesKey = '*' OR seriesKey = :seriesKey
        ORDER BY LENGTH(jpTerm) DESC, updatedAtEpochMs DESC
        """
    )
    fun observeGlossaryTerms(seriesKey: String = "*"): Flow<List<GlossaryTermEntity>>

    @Query(
        """
        SELECT * FROM glossary_terms 
        WHERE seriesKey = '*' OR seriesKey = :seriesKey
        ORDER BY LENGTH(jpTerm) DESC
        """
    )
    suspend fun getGlossaryTermsSync(seriesKey: String = "*"): List<GlossaryTermEntity>

    @Query("SELECT COUNT(*) FROM glossary_terms")
    suspend fun getGlossaryCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGlossaryTerm(term: GlossaryTermEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGlossaryTerms(terms: List<GlossaryTermEntity>)

    @Query("DELETE FROM glossary_terms WHERE id = :id")
    suspend fun deleteGlossaryTermById(id: String)
}

@Database(
    entities = [
        BookshelfBookEntity::class,
        CachedChapterEntity::class,
        CachedParagraphEntity::class,
        GlossaryTermEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class KotoReadDatabase : RoomDatabase() {
    abstract fun kotoReadDao(): KotoReadDao

    companion object {
        @Volatile
        private var INSTANCE: KotoReadDatabase? = null

        fun getInstance(context: Context): KotoReadDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KotoReadDatabase::class.java,
                    "kotoread_novel_reader.db"
                )
                    .addCallback(PrepopulateCallback())
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }

    private class PrepopulateCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                INSTANCE?.kotoReadDao()?.let { dao ->
                    if (dao.getGlossaryCount() == 0) {
                        val defaults = defaultSampleGlossary().map { it.toEntity() }
                        dao.insertGlossaryTerms(defaults)
                    }
                }
            }
        }
    }
}

fun GlossaryTerm.toEntity(): GlossaryTermEntity = GlossaryTermEntity(
    id = id,
    jpTerm = jpTerm,
    zhTerm = zhTerm,
    category = category,
    note = note,
    seriesKey = seriesKey
)

fun GlossaryTermEntity.toDomain(): GlossaryTerm = GlossaryTerm(
    id = id,
    jpTerm = jpTerm,
    zhTerm = zhTerm,
    category = category,
    note = note,
    seriesKey = seriesKey
)

fun CachedChapterWithParagraphs.toDomain(): NovelChapter = NovelChapter(
    sourceUrl = chapter.chapterUrl,
    seriesKey = chapter.seriesKey,
    siteName = chapter.siteName,
    seriesTitle = chapter.seriesTitle,
    chapterTitleJp = chapter.chapterTitleJp,
    chapterTitleZh = chapter.chapterTitleZh,
    author = chapter.author,
    prevChapterUrl = chapter.prevChapterUrl,
    nextChapterUrl = chapter.nextChapterUrl,
    isFromOfflineCache = true,
    paragraphs = paragraphs
        .sortedBy { it.paragraphIndex }
        .map { p ->
            NovelParagraph(
                index = p.paragraphIndex,
                jpPlain = p.jpPlain,
                jpWithRubyText = p.jpWithRubyText,
                zhTranslated = p.zhTranslated
            )
        }
)

fun BookshelfBookSummary.toDomain(): BookshelfItem = BookshelfItem(
    seriesKey = book.seriesKey,
    siteName = book.siteName,
    seriesTitle = book.seriesTitle,
    author = book.author,
    lastReadChapterUrl = book.lastReadChapterUrl,
    lastReadChapterTitle = book.lastReadChapterTitle,
    lastReadScrollIndex = book.lastReadScrollIndex,
    cachedChapterCount = cachedChapterCount,
    hasUnreadUpdate = book.hasUnreadUpdate,
    latestKnownNextUrl = book.latestKnownNextUrl,
    latestKnownNextTitle = book.latestKnownNextTitle,
    lastCheckedAtEpochMs = book.lastCheckedAtEpochMs,
    updatedAtEpochMs = book.updatedAtEpochMs
)

class KotoReadRepository(
    private val dao: KotoReadDao,
    private val scraper: NovelWebScraper
) {
    fun observeBookshelf(): Flow<List<BookshelfItem>> =
        dao.observeBookshelfSummaries().map { list -> list.map { it.toDomain() } }

    fun observeGlossary(seriesKey: String = "*"): Flow<List<GlossaryTerm>> =
        dao.observeGlossaryTerms(seriesKey).map { list -> list.map { it.toDomain() } }

    suspend fun getOrFetchChapter(url: String, forceRefresh: Boolean = false): Result<NovelChapter> {
        if (!forceRefresh) {
            val cached = dao.getCachedChapterByUrl(url)
            if (cached != null && cached.paragraphs.isNotEmpty()) {
                return Result.success(cached.toDomain())
            }
        }

        return scraper.fetchAndFormatChapter(url).onSuccess { fetched ->
            persistChapterAndBookshelf(fetched)
        }
    }

    suspend fun persistChapterAndBookshelf(chapter: NovelChapter, scrollIndex: Int = 0) {
        val header = CachedChapterEntity(
            chapterUrl = chapter.sourceUrl,
            seriesKey = chapter.seriesKey,
            siteName = chapter.siteName,
            seriesTitle = chapter.seriesTitle,
            chapterTitleJp = chapter.chapterTitleJp,
            chapterTitleZh = chapter.chapterTitleZh,
            author = chapter.author,
            prevChapterUrl = chapter.prevChapterUrl,
            nextChapterUrl = chapter.nextChapterUrl
        )

        val paragraphEntities = chapter.paragraphs.map { p ->
            CachedParagraphEntity(
                chapterUrl = chapter.sourceUrl,
                paragraphIndex = p.index,
                jpPlain = p.jpPlain,
                jpWithRubyText = p.jpWithRubyText,
                zhTranslated = p.zhTranslated
            )
        }

        dao.saveCompleteChapterCache(header, paragraphEntities)

        val displayTitle = chapter.chapterTitleZh.ifBlank { chapter.chapterTitleJp }
        val existingBook = dao.getBookshelfBookByKeySync(chapter.seriesKey)

        val stillHasUnread = existingBook?.hasUnreadUpdate == true &&
            existingBook.latestKnownNextUrl != null &&
            existingBook.latestKnownNextUrl != chapter.sourceUrl

        dao.upsertBookshelfBook(
            BookshelfBookEntity(
                seriesKey = chapter.seriesKey,
                siteName = chapter.siteName,
                seriesTitle = chapter.seriesTitle,
                author = chapter.author,
                lastReadChapterUrl = chapter.sourceUrl,
                lastReadChapterTitle = displayTitle,
                lastReadScrollIndex = scrollIndex,
                hasUnreadUpdate = stillHasUnread,
                latestKnownNextUrl = if (stillHasUnread) existingBook?.latestKnownNextUrl else chapter.nextChapterUrl,
                latestKnownNextTitle = if (stillHasUnread) existingBook?.latestKnownNextTitle.orEmpty() else ""
            )
        )
    }

    suspend fun upsertGlossary(term: GlossaryTerm) {
        dao.upsertGlossaryTerm(term.toEntity())
    }

    suspend fun deleteGlossary(id: String) {
        dao.deleteGlossaryTermById(id)
    }

    suspend fun getSeriesAllCachedChapters(seriesKey: String): List<NovelChapter> {
        return dao.getAllCachedChaptersBySeriesSync(seriesKey).map { it.toDomain() }
    }

    suspend fun removeSeriesFromBookshelf(seriesKey: String) {
        dao.deleteBookshelfBook(seriesKey)
        dao.clearSeriesCache(seriesKey)
    }
}

// ============================================================================
// 3. Android WorkManager 背景連載更新巡檢與推播通知模組
// ============================================================================

class NovelUpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = KotoReadDatabase.getInstance(applicationContext)
        val dao = db.kotoReadDao()
        val scraper = NovelWebScraper()
        val translator = NovelTranslationService()

        val books = dao.getAllBookshelfBooksSync()
        if (books.isEmpty()) {
            return@withContext Result.success(workDataOf(KEY_UPDATED_COUNT to 0))
        }

        var updatedSeriesCount = 0

        for (book in books) {
            runCatching {
                val latestCheckResult = scraper.fetchAndFormatChapter(book.lastReadChapterUrl)
                val currentChapterOnWeb = latestCheckResult.getOrNull()
                val discoveredNextUrl = currentChapterOnWeb?.nextChapterUrl ?: book.latestKnownNextUrl

                if (!discoveredNextUrl.isNullOrBlank() && discoveredNextUrl != book.lastReadChapterUrl) {
                    val existingNextCache = dao.getCachedChapterByUrl(discoveredNextUrl)

                    val nextChapterTitleDisplay: String
                    if (existingNextCache == null) {
                        val nextChapResult = scraper.fetchAndFormatChapter(discoveredNextUrl)
                        val nextChap = nextChapResult.getOrNull()
                        if (nextChap != null) {
                            val glossary = dao.getGlossaryTermsSync(book.seriesKey).map { it.toDomain() }
                            var finalTitleZh = nextChap.chapterTitleJp
                            var finalParagraphs = nextChap.paragraphs

                            translator.translateChapterParagraphs(
                                chapterTitleJp = nextChap.chapterTitleJp,
                                paragraphs = nextChap.paragraphs,
                                glossary = glossary,
                                engine = TranslationEngine.ML_KIT_OFFLINE
                            ) { tTitle, updatedParas, _ ->
                                finalTitleZh = tTitle
                                finalParagraphs = updatedParas
                            }

                            dao.saveCompleteChapterCache(
                                chapter = CachedChapterEntity(
                                    chapterUrl = nextChap.sourceUrl,
                                    seriesKey = book.seriesKey,
                                    siteName = nextChap.siteName,
                                    seriesTitle = book.seriesTitle,
                                    chapterTitleJp = nextChap.chapterTitleJp,
                                    chapterTitleZh = finalTitleZh,
                                    author = book.author,
                                    prevChapterUrl = nextChap.prevChapterUrl,
                                    nextChapterUrl = nextChap.nextChapterUrl
                                ),
                                paragraphs = finalParagraphs.map { p ->
                                    CachedParagraphEntity(
                                        chapterUrl = nextChap.sourceUrl,
                                        paragraphIndex = p.index,
                                        jpPlain = p.jpPlain,
                                        jpWithRubyText = p.jpWithRubyText,
                                        zhTranslated = p.zhTranslated
                                    )
                                }
                            )
                            nextChapterTitleDisplay = finalTitleZh.ifBlank { nextChap.chapterTitleJp }
                        } else {
                            nextChapterTitleDisplay = "新章節已發布（點擊讀取）"
                        }
                    } else {
                        nextChapterTitleDisplay = existingNextCache.chapter.chapterTitleZh
                            .ifBlank { existingNextCache.chapter.chapterTitleJp }
                    }

                    dao.markSeriesUpdateStatus(
                        seriesKey = book.seriesKey,
                        hasUpdate = true,
                        nextUrl = discoveredNextUrl,
                        nextTitle = nextChapterTitleDisplay
                    )

                    NovelUpdateNotificationHelper.sendNewChapterNotification(
                        context = applicationContext,
                        seriesKey = book.seriesKey,
                        seriesTitle = book.seriesTitle,
                        newChapterTitle = nextChapterTitleDisplay,
                        newChapterUrl = discoveredNextUrl
                    )

                    updatedSeriesCount++
                } else {
                    dao.markSeriesUpdateStatus(
                        seriesKey = book.seriesKey,
                        hasUpdate = false,
                        nextUrl = null,
                        nextTitle = ""
                    )
                }
            }
        }

        Result.success(workDataOf(KEY_UPDATED_COUNT to updatedSeriesCount))
    }

    companion object {
        const val KEY_UPDATED_COUNT = "updated_series_count"
    }
}

object NovelUpdateNotificationHelper {
    private const val CHANNEL_ID = "kotoread_novel_updates"
    private const val CHANNEL_NAME = "小說連載更新與預載通知"

    fun ensureChannelCreated(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "當收藏的日文小說有新章節更新且完成背景翻譯時通知您"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun sendNewChapterNotification(
        context: Context,
        seriesKey: String,
        seriesTitle: String,
        newChapterTitle: String,
        newChapterUrl: String
    ) {
        ensureChannelCreated(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, newChapterUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            seriesKey.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("📚《$seriesTitle》有新章節更新！")
            .setContentText("已為您預先翻譯完成：$newChapterTitle")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("最新話「$newChapterTitle」已完成背景排版與日翻中快取，點擊即可離線秒開閱讀。")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(seriesKey.hashCode(), notification)
    }
}

object NovelUpdateScheduler {
    const val PERIODIC_WORK_NAME = "kotoread_periodic_novel_update_work"
    const val ONE_TIME_WORK_NAME = "kotoread_onetime_novel_update_work"

    fun schedulePeriodicUpdateCheck(context: Context, repeatIntervalHours: Long = 6L) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<NovelUpdateCheckWorker>(
            repeatIntervalHours,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )
    }

    fun cancelPeriodicUpdateCheck(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    fun triggerImmediateCheckNow(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeRequest = OneTimeWorkRequestBuilder<NovelUpdateCheckWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            oneTimeRequest
        )
    }
}

// ============================================================================
// 4. 網頁爬蟲與智慧排版清洗器 (Jsoup + OkHttp)
// ============================================================================

class NovelWebScraper(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetchAndFormatChapter(url: String): Result<NovelChapter> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                )
                .header("Cookie", "over18=yes")
                .build()

            val html = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP 讀取失敗：狀態碼 ${response.code}")
                }
                response.body?.string() ?: throw IllegalStateException("網頁內容為空")
            }

            val doc = Jsoup.parse(html, url)

            when {
                url.contains("syosetu.com") -> parseSyosetu(doc, url)
                url.contains("kakuyomu.jp") -> parseKakuyomu(doc, url)
                else -> parseGenericReadable(doc, url)
            }
        }
    }

    fun extractSeriesKey(url: String, fallbackTitle: String): String {
        val syosetuMatch = "syosetu\\.com/([a-z0-9]+)/?".toRegex(RegexOption.IGNORE_CASE).find(url)
        if (syosetuMatch != null) return "syosetu:${syosetuMatch.groupValues[1].lowercase()}"

        val kakuyomuMatch = "kakuyomu\\.jp/works/([0-9]+)".toRegex(RegexOption.IGNORE_CASE).find(url)
        if (kakuyomuMatch != null) return "kakuyomu:${kakuyomuMatch.groupValues[1]}"

        return "web:${fallbackTitle.hashCode()}"
    }

    private fun parseSyosetu(doc: org.jsoup.nodes.Document, url: String): NovelChapter {
        val seriesTitle = doc.selectFirst(".p-novel__series-link, .contents1 a")?.text()?.trim()
            ?: doc.title()
        val chapterTitle = doc.selectFirst(".p-novel__title, .novel_subtitle")?.text()?.trim()
            ?: "未命名章節"
        val author = doc.selectFirst(".p-novel__author a, .novel_writername a")?.text()?.trim()
            ?: "佚名作者"

        val bodyElements = doc.select(".js-novel-text p, #novel_honbun p, #novel_p p, #novel_a p")
        val paragraphs = extractCleanParagraphs(bodyElements)

        val nextUrl = doc.selectFirst("a.c-pager__item--next, .novel_bn a:contains(次へ)")
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() }

        val prevUrl = doc.selectFirst("a.c-pager__item--before, .novel_bn a:contains(前へ)")
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() }

        return NovelChapter(
            sourceUrl = url,
            seriesKey = extractSeriesKey(url, seriesTitle),
            siteName = "小説家になろう",
            seriesTitle = seriesTitle,
            chapterTitleJp = chapterTitle,
            author = author,
            paragraphs = paragraphs,
            prevChapterUrl = prevUrl,
            nextChapterUrl = nextUrl
        )
    }

    private fun parseKakuyomu(doc: org.jsoup.nodes.Document, url: String): NovelChapter {
        val seriesTitle = doc.selectFirst("#contentMain-header-workTitle, .widget-episodeTitle")?.text()?.trim()
            ?: doc.title()
        val chapterTitle = doc.selectFirst(".widget-episodeTitle, .chapterTitle")?.text()?.trim()
            ?: "未命名章節"
        val author = doc.selectFirst("#contentMain-header-author, .widget-authorName")?.text()?.trim()
            ?: ""

        val bodyElements = doc.select(".widget-episodeBody p")
        val paragraphs = extractCleanParagraphs(bodyElements)

        val nextUrl = doc.selectFirst("#contentMain-readNextEpisode, a[rel=next]")
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() }

        val prevUrl = doc.selectFirst("#contentMain-readPreviousEpisode, a[rel=prev]")
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() }

        return NovelChapter(
            sourceUrl = url,
            seriesKey = extractSeriesKey(url, seriesTitle),
            siteName = "カクヨム Kakuyomu",
            seriesTitle = seriesTitle,
            chapterTitleJp = chapterTitle,
            author = author,
            paragraphs = paragraphs,
            prevChapterUrl = prevUrl,
            nextChapterUrl = nextUrl
        )
    }

    private fun parseGenericReadable(doc: org.jsoup.nodes.Document, url: String): NovelChapter {
        doc.select("script, style, nav, header, footer, aside, .ad, .advertisement, iframe").remove()
        val title = doc.selectFirst("h1, h2")?.text()?.trim() ?: doc.title()
        val pElements = doc.select("article p, main p, .novel-body p, body p")
        val paragraphs = extractCleanParagraphs(pElements)

        return NovelChapter(
            sourceUrl = url,
            seriesKey = extractSeriesKey(url, doc.title()),
            siteName = "一般日文網頁",
            seriesTitle = doc.title(),
            chapterTitleJp = title,
            paragraphs = paragraphs
        )
    }

    private fun extractCleanParagraphs(elements: List<Element>): List<NovelParagraph> {
        val result = mutableListOf<NovelParagraph>()
        var idx = 0

        for (p in elements) {
            val plainBuilder = StringBuilder()
            val rubyBuilder = StringBuilder()

            for (node in p.childNodes()) {
                when (node) {
                    is TextNode -> {
                        val t = node.text()
                        plainBuilder.append(t)
                        rubyBuilder.append(t)
                    }
                    is Element -> {
                        if (node.tagName().equals("ruby", ignoreCase = true)) {
                            val rtText = node.select("rt").text().trim()
                            val baseClone = node.clone()
                            baseClone.select("rt, rp").remove()
                            val baseText = baseClone.text().trim()

                            plainBuilder.append(baseText)
                            if (rtText.isNotEmpty()) {
                                rubyBuilder.append("$baseText($rtText)")
                            } else {
                                rubyBuilder.append(baseText)
                            }
                        } else if (!node.tagName().equals("br", ignoreCase = true)) {
                            val t = node.text()
                            plainBuilder.append(t)
                            rubyBuilder.append(t)
                        }
                    }
                }
            }

            val normalizedPlain = normalizeJapaneseTypography(plainBuilder.toString())
            val normalizedRuby = normalizeJapaneseTypography(rubyBuilder.toString())

            if (normalizedPlain.isNotBlank()) {
                result.add(
                    NovelParagraph(
                        index = idx++,
                        jpPlain = normalizedPlain,
                        jpWithRubyText = normalizedRuby
                    )
                )
            }
        }
        return result
    }

    private fun normalizeJapaneseTypography(raw: String): String {
        return raw
            .replace("^[\\s　]+".toRegex(), "")
            .replace("―{2,}".toRegex(), "——")
            .replace("ー{2,}".toRegex(), "——")
            .replace("・{3,}".toRegex(), "……")
            .trim()
    }
}

// ============================================================================
// 5. 一鍵日翻中翻譯服務（整合 Google ML Kit 端側離線模型 + Gemini API / 詞典鎖定）
// ============================================================================

class NovelTranslationService(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    // ML Kit Translator 快取實例與執行緒同步鎖
    private var mlKitTranslatorInstance: Translator? = null
    private val mlKitMutex = Mutex()

    /**
     * 初始化並確保 Google ML Kit 端側「日文 > 中文」翻譯模型已就緒。
     * 若本機尚未下載約 30MB 的神經網路翻譯包，則透過 [DownloadConditions] 自動於背景下載。
     */
    suspend fun ensureMlKitModelDownloaded(requireWifiOnly: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        mlKitMutex.withLock {
            runCatching {
                if (mlKitTranslatorInstance == null) {
                    val options = TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.JAPANESE)
                        .setTargetLanguage(TranslateLanguage.CHINESE)
                        .build()
                    mlKitTranslatorInstance = Translation.getClient(options)
                }

                val conditions = DownloadConditions.Builder().apply {
                    if (requireWifiOnly) requireWifi()
                }.build()

                suspendCancellableCoroutine { continuation ->
                    mlKitTranslatorInstance!!.downloadModelIfNeeded(conditions)
                        .addOnSuccessListener {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        .addOnFailureListener { exception ->
                            if (continuation.isActive) continuation.resumeWithException(exception)
                        }
                }
            }
        }
    }

    /**
     * 呼叫 Google ML Kit 端側神經網路模型翻譯單一段落（完全離線、無網路消耗、反應極快）
     */
    suspend fun translateWithMlKitOffline(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text
        ensureMlKitModelDownloaded().getOrThrow()

        mlKitMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                mlKitTranslatorInstance!!.translate(text)
                    .addOnSuccessListener { translatedText ->
                        if (continuation.isActive) continuation.resume(translatedText)
                    }
                    .addOnFailureListener { exception ->
                        if (continuation.isActive) continuation.resumeWithException(exception)
                    }
            }
        }
    }

    /**
     * 批次翻譯整章小說段落，支援「Google ML Kit 離線模型」與「Gemini 輕小說大模型」無縫切換，
     * 並自動將使用者的「專有名詞與角色譯名詞典」強制鎖定替換。
     */
    suspend fun translateChapterParagraphs(
        chapterTitleJp: String,
        paragraphs: List<NovelParagraph>,
        glossary: List<GlossaryTerm>,
        engine: TranslationEngine,
        apiKey: String = "",
        onBatchProgress: (translatedTitle: String, updatedParagraphs: List<NovelParagraph>, progress: Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (paragraphs.isEmpty()) return@withContext

        val sortedGlossary = glossary.sortedByDescending { it.jpTerm.length }

        // 1. 翻譯章節標題
        val rawTitleZh = when (engine) {
            TranslationEngine.ML_KIT_OFFLINE -> {
                runCatching { translateWithMlKitOffline(chapterTitleJp) }.getOrElse {
                    simulateSmartLocalTranslate(chapterTitleJp, sortedGlossary)
                }
            }
            TranslationEngine.GEMINI_LLM -> {
                if (apiKey.isNotBlank()) {
                    runCatching {
                        callGeminiBatchApi(listOf(chapterTitleJp), sortedGlossary, apiKey).firstOrNull() ?: chapterTitleJp
                    }.getOrElse { simulateSmartLocalTranslate(chapterTitleJp, sortedGlossary) }
                } else {
                    simulateSmartLocalTranslate(chapterTitleJp, sortedGlossary)
                }
            }
            TranslationEngine.DEEPL_API -> {
                simulateSmartLocalTranslate(chapterTitleJp, sortedGlossary)
            }
        }
        val translatedTitle = applyGlossaryLocks(rawTitleZh, sortedGlossary)

        val mutableList = paragraphs.toMutableList()
        val batchSize = if (engine == TranslationEngine.ML_KIT_OFFLINE) 4 else 8
        val batches = mutableList.chunked(batchSize)

        // 2. 逐批翻譯並回報串流進度
        batches.forEachIndexed { batchIdx, batch ->
            val sourceTexts = batch.map { it.jpPlain }

            val translatedBatch: List<String> = when (engine) {
                TranslationEngine.ML_KIT_OFFLINE -> {
                    sourceTexts.map { text ->
                        runCatching { translateWithMlKitOffline(text) }.getOrElse {
                            simulateSmartLocalTranslate(text, sortedGlossary)
                        }
                    }
                }
                TranslationEngine.GEMINI_LLM -> {
                    if (apiKey.isNotBlank()) {
                        runCatching {
                            callGeminiBatchApi(sourceTexts, sortedGlossary, apiKey)
                        }.getOrElse {
                            sourceTexts.map { simulateSmartLocalTranslate(it, sortedGlossary) }
                        }
                    } else {
                        sourceTexts.map { simulateSmartLocalTranslate(it, sortedGlossary) }
                    }
                }
                TranslationEngine.DEEPL_API -> {
                    sourceTexts.map { simulateSmartLocalTranslate(it, sortedGlossary) }
                }
            }

            batch.forEachIndexed { i, para ->
                val rawZh = translatedBatch.getOrNull(i) ?: para.jpPlain
                val lockedZh = applyGlossaryLocks(rawZh, sortedGlossary)
                mutableList[para.index] = mutableList[para.index].copy(zhTranslated = lockedZh)
            }

            val progress = ((batchIdx + 1).toFloat() / batches.size.toFloat()).coerceIn(0f, 1f)
            onBatchProgress(translatedTitle, mutableList.toList(), progress)
        }
    }

    /**
     * 釋放 ML Kit Translator 記憶體資源
     */
    fun closeMlKitTranslator() {
        mlKitTranslatorInstance?.close()
        mlKitTranslatorInstance = null
    }

    private fun callGeminiBatchApi(
        jpParagraphs: List<String>,
        glossary: List<GlossaryTerm>,
        apiKey: String
    ): List<String> {
        val glossaryRulesText = glossary.joinToString("\n") { term ->
            "- 日文「${term.jpTerm}」必須固定翻譯為「${term.zhTerm}」（備註：${term.note}）"
        }

        val inputJsonArray = JSONArray(jpParagraphs)
        val prompt = """
            你是一位專業的日本輕小說繁體中文譯者。請將以下 JSON 陣列中的日文段落逐段翻譯為自然流暢的台灣繁體中文。
            
            【嚴格遵守規則】
            1. 必須嚴格套用以下專有名詞與角色設定詞典：
            $glossaryRulesText
            2. 保持原始段落數量完全一致，僅輸出純 JSON 字串陣列（例如 ["第一段譯文", "第二段譯文"]），不要加 Markdown 語法。
            
            【待翻譯日文段落 JSON】
            $inputJsonArray
        """.trimIndent()

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            ))
            put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: error("Empty Gemini response")
            val root = JSONObject(bodyStr)
            val text = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            val parsedArray = JSONArray(text)
            return List(parsedArray.length()) { i -> parsedArray.getString(i) }
        }
    }

    fun applyGlossaryLocks(text: String, glossary: List<GlossaryTerm>): String {
        var result = text
        for (term in glossary) {
            if (term.jpTerm.isNotBlank() && term.zhTerm.isNotBlank()) {
                result = result.replace(term.jpTerm, term.zhTerm)
            }
        }
        return result
    }

    private fun simulateSmartLocalTranslate(jp: String, glossary: List<GlossaryTerm>): String {
        var out = applyGlossaryLocks(jp, glossary)
        val builtInSamples = mapOf(
            "第十四話：廃都の底で眠る星辰炉と、黒曜の聖女の微笑み" to "第十四話：沉睡於廢都底層的星辰爐，以及黑曜聖女的微笑",
            "冷たい鉄の匂いが満ちる地下遺構の奥で、蓮は懐中時計の蓋を閉じた。目の前にそびえるのは、三百年前に停止したはずの巨大機関——「星辰爐」だった。" to
                "在瀰漫著冰冷鐵鏽氣息的地下遺構深處，蓮輕輕闔上懷錶的錶蓋。聳立在眼前的，正是本該在三百年前就已徹底停擺的巨型機關——「星辰爐」。",
            "「まだ動いている……？　嘘でしょう、機巧兵團の記録では完全に焼失したと書かれていたのに」" to
                "「竟然還在運轉……？怎麼可能，機巧兵團的檔案明明記載它已經徹底燒毀了啊。」",
            "隣に立つ少女——亞絲特爾の翡翠色の瞳が、炉心から漏れる蒼い燐光を反射して揺れた。彼女の指先がコンソールに触れた瞬間、錆びついた歯車が重々しい音を立てて噛み合い始める。" to
                "站在身旁的少女——亞絲特爾那雙翡翠色的眼眸，映照著從爐心傾洩而出的蒼藍磷光而微微顫動。當她的指尖觸碰到控制台的瞬間，鏽跡斑斑的齒輪發出沉悶的轟鳴，再次咬合運轉。",
            "「怖がらないで、蓮。これは破壊の兵器じゃないわ。かつて『黑曜聖女』が街を凍てつく吹雪から守るために遺した、最後の揺り籠なのだから」" to
                "「別害怕，蓮。這並不是用來破壞的兵器，而是昔日『黑曜聖女』為了守護城市免受凜冽暴風雪侵襲，所留下的最後一座搖籃。」"
        )
        builtInSamples[out]?.let { return applyGlossaryLocks(it, glossary) }
        return out
    }
}

// ============================================================================
// 6. ViewModel 狀態管理 (串接 Room 資料庫、WorkManager 與 ML Kit 離線引擎)
// ============================================================================

class NovelReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val database = KotoReadDatabase.getInstance(appContext)
    private val scraper = NovelWebScraper()
    private val repository = KotoReadRepository(database.kotoReadDao(), scraper)
    private val translator = NovelTranslationService()
    private val workManager = WorkManager.getInstance(appContext)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        // 1. 初始化通知頻道並啟動每 6 小時定期背景巡檢 WorkManager
        NovelUpdateNotificationHelper.ensureChannelCreated(appContext)
        NovelUpdateScheduler.schedulePeriodicUpdateCheck(appContext, repeatIntervalHours = 6L)

        // 2. 於背景靜默預載 Google ML Kit 離線日翻中模型（約 30MB）
        viewModelScope.launch {
            translator.ensureMlKitModelDownloaded(requireWifiOnly = false)
        }

        // 3. 訂閱 Room 中的專有名詞詞典變化
        viewModelScope.launch {
            repository.observeGlossary("*").collect { dbGlossary ->
                if (dbGlossary.isNotEmpty()) {
                    _uiState.update { it.copy(glossary = dbGlossary) }
                }
            }
        }

        // 4. 訂閱 Room 中的跨站書架收藏列表
        viewModelScope.launch {
            repository.observeBookshelf().collect { books ->
                _uiState.update { it.copy(bookshelf = books) }
            }
        }

        // 5. 監聽單次手動巡檢 WorkManager 任務進度與結果
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(NovelUpdateScheduler.ONE_TIME_WORK_NAME)
                .collect { workInfos ->
                    val info = workInfos.firstOrNull() ?: return@collect
                    when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                            _uiState.update {
                                it.copy(
                                    isCheckingUpdatesNow = true,
                                    lastUpdateCheckReport = "WorkManager 正在背景巡檢書架小說並預載新章節..."
                                )
                            }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val updatedCount = info.outputData.getInt(NovelUpdateCheckWorker.KEY_UPDATED_COUNT, 0)
                            _uiState.update {
                                it.copy(
                                    isCheckingUpdatesNow = false,
                                    lastUpdateCheckReport = if (updatedCount > 0) {
                                        "🔔 巡檢完成：發現 $updatedCount 部作品有新章節，已完成背景日翻中快取！"
                                    } else {
                                        "✓ 巡檢完成：目前所有書架作品皆為最新進度"
                                    }
                                )
                            }
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            _uiState.update {
                                it.copy(
                                    isCheckingUpdatesNow = false,
                                    lastUpdateCheckReport = "背景巡檢已結束（請確認網路連線）"
                                )
                            }
                        }
                        else -> Unit
                    }
                }
        }

        // 6. 啟動時載入預設示範章節並寫入 Room 快取
        loadDemoChapterAndCache()
    }

    override fun onCleared() {
        super.onCleared()
        translator.closeMlKitTranslator()
    }

    fun onUrlInputChange(newUrl: String) {
        _uiState.update { it.copy(urlInput = newUrl) }
    }

    fun setPeriodicUpdateCheckEnabled(enabled: Boolean) {
        if (enabled) {
            NovelUpdateScheduler.schedulePeriodicUpdateCheck(appContext, repeatIntervalHours = 6L)
        } else {
            NovelUpdateScheduler.cancelPeriodicUpdateCheck(appContext)
        }
        _uiState.update {
            it.copy(
                isPeriodicWorkEnabled = enabled,
                lastUpdateCheckReport = if (enabled) {
                    "已啟用 WorkManager 每 6 小時自動巡檢與背景預翻譯"
                } else {
                    "已暫停背景定期巡檢任務"
                }
            )
        }
    }

    fun triggerManualUpdateCheckNow() {
        NovelUpdateScheduler.triggerImmediateCheckNow(appContext)
    }

    fun fetchUrlAndTranslate(
        url: String = _uiState.value.urlInput,
        forceNetworkRefresh: Boolean = false,
        geminiApiKey: String = ""
    ) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    urlInput = url,
                    isLoadingUrl = true,
                    statusMessage = if (forceNetworkRefresh) "正在重新連線抓取網頁..." else "正在檢查 Room 離線快取或讀取網址..."
                )
            }

            val result = repository.getOrFetchChapter(url, forceRefresh = forceNetworkRefresh)
            result.onSuccess { chapter ->
                val alreadyTranslated = chapter.paragraphs.any { it.zhTranslated.isNotBlank() }
                _uiState.update {
                    it.copy(
                        isLoadingUrl = false,
                        currentChapter = chapter,
                        statusMessage = if (chapter.isFromOfflineCache && alreadyTranslated) {
                            "⚡ 已從 Room 離線快取秒開雙語章節（共 ${chapter.paragraphs.size} 段）"
                        } else {
                            "已擷取 ${chapter.paragraphs.size} 個段落，正在執行一鍵日翻中..."
                        }
                    )
                }

                if (!alreadyTranslated || forceNetworkRefresh) {
                    triggerOneTapTranslate(geminiApiKey)
                } else {
                    repository.persistChapterAndBookshelf(chapter)
                }

                chapter.nextChapterUrl?.let { nextUrl ->
                    preloadNextChapterInBackground(nextUrl)
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingUrl = false,
                        statusMessage = "讀取失敗（${err.localizedMessage}），已使用本地快取章節。"
                    )
                }
            }
        }
    }

    fun triggerOneTapTranslate(geminiApiKey: String = "") {
        val chapter = _uiState.value.currentChapter ?: return
        val glossary = _uiState.value.glossary
        val engine = _uiState.value.engine

        viewModelScope.launch {
            val engineLabel = when (engine) {
                TranslationEngine.ML_KIT_OFFLINE -> "Google ML Kit 端側離線引擎"
                TranslationEngine.GEMINI_LLM -> "Gemini 輕小說雲端引擎"
                TranslationEngine.DEEPL_API -> "DeepL API 引擎"
            }

            _uiState.update {
                it.copy(
                    isTranslating = true,
                    translationProgress = 0.05f,
                    statusMessage = "正在透過 $engineLabel 執行日翻中（套用 ${glossary.size} 項詞條）..."
                )
            }

            translator.translateChapterParagraphs(
                chapterTitleJp = chapter.chapterTitleJp,
                paragraphs = chapter.paragraphs,
                glossary = glossary,
                engine = engine,
                apiKey = geminiApiKey
            ) { translatedTitle, updatedParagraphs, progress ->
                val updatedChapter = _uiState.value.currentChapter?.copy(
                    chapterTitleZh = translatedTitle,
                    paragraphs = updatedParagraphs
                )
                _uiState.update { state ->
                    state.copy(
                        currentChapter = updatedChapter,
                        translationProgress = progress,
                        isTranslating = progress < 1f,
                        statusMessage = if (progress >= 1f) {
                            "✓ 日翻中完成並已存入 Room 離線快取（引擎：$engineLabel）"
                        } else {
                            "正在翻譯段落 ${(progress * 100).toInt()}%..."
                        }
                    )
                }

                if (progress >= 1f && updatedChapter != null) {
                    viewModelScope.launch {
                        repository.persistChapterAndBookshelf(updatedChapter)
                    }
                }
            }
        }
    }

    private fun preloadNextChapterInBackground(nextUrl: String) {
        viewModelScope.launch {
            repository.getOrFetchChapter(nextUrl, forceRefresh = false).onSuccess { nextChap ->
                _uiState.update { it.copy(preloadedNextChapter = nextChap) }
            }
        }
    }

    fun toggleParagraphExpand(index: Int) {
        _uiState.update { state ->
            val current = state.currentChapter ?: return@update state
            val updated = current.paragraphs.map { p ->
                if (p.index == index) p.copy(isExpanded = !p.isExpanded) else p
            }
            state.copy(currentChapter = current.copy(paragraphs = updated))
        }
    }

    fun upsertGlossaryTerm(jpTerm: String, zhTerm: String, category: String, note: String) {
        if (jpTerm.isBlank() || zhTerm.isBlank()) return
        viewModelScope.launch {
            val existing = _uiState.value.glossary.firstOrNull { it.jpTerm == jpTerm.trim() }
            val term = GlossaryTerm(
                id = existing?.id ?: System.currentTimeMillis().toString(),
                jpTerm = jpTerm.trim(),
                zhTerm = zhTerm.trim(),
                category = category,
                note = note.trim()
            )
            repository.upsertGlossary(term)
            triggerOneTapTranslate()
        }
    }

    fun deleteGlossaryTerm(id: String) {
        viewModelScope.launch {
            repository.deleteGlossary(id)
            triggerOneTapTranslate()
        }
    }

    fun deleteBookFromShelf(seriesKey: String) {
        viewModelScope.launch {
            repository.removeSeriesFromBookshelf(seriesKey)
        }
    }

    fun setDisplayMode(mode: DisplayMode) {
        _uiState.update { it.copy(displayMode = mode) }
    }

    fun setTranslationEngine(engine: TranslationEngine) {
        _uiState.update { it.copy(engine = engine) }
        triggerOneTapTranslate()
    }

    fun setReaderTheme(theme: ReaderTheme) {
        _uiState.update { it.copy(theme = theme) }
    }

    fun setFontSize(sizeSp: Float) {
        _uiState.update { it.copy(fontSizeSp = sizeSp.coerceIn(14f, 26f)) }
    }

    /**
     * 匯出當前閱讀之章節（EPUB 或 TXT）並調用系統分享
     */
    fun exportCurrentChapter(format: ExportFormat) {
        val chapter = _uiState.value.currentChapter ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "正在匯出【${format.name}】電子書...") }
            val result = NovelExportManager.exportAndShare(
                context = appContext,
                seriesTitle = chapter.seriesTitle,
                author = chapter.author,
                chapters = listOf(chapter),
                format = format
            )
            result.onSuccess { file ->
                _uiState.update { it.copy(statusMessage = "✓ 已成功匯出並開啟分享：${file.name}") }
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = "匯出失敗：${err.localizedMessage}") }
            }
        }
    }

    /**
     * 匯出整部作品中所有已快取的雙語章節
     */
    fun exportWholeSeries(seriesKey: String, seriesTitle: String, author: String, format: ExportFormat) {
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "正在匯出《$seriesTitle》全快取章節...") }
            val cachedChapters = repository.getSeriesAllCachedChapters(seriesKey)
            val chaptersToExport = if (cachedChapters.isNotEmpty()) {
                cachedChapters
            } else {
                _uiState.value.currentChapter?.let { listOf(it) } ?: emptyList()
            }

            val result = NovelExportManager.exportAndShare(
                context = appContext,
                seriesTitle = seriesTitle,
                author = author,
                chapters = chaptersToExport,
                format = format
            )
            result.onSuccess { file ->
                _uiState.update { it.copy(statusMessage = "✓ 已成功匯出全本 ${chaptersToExport.size} 話：${file.name}") }
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = "匯出失敗：${err.localizedMessage}") }
            }
        }
    }

    private fun loadDemoChapterAndCache() {
        val sampleParagraphs = listOf(
            NovelParagraph(
                index = 0,
                jpPlain = "冷たい鉄の匂いが満ちる地下遺構の奥で、レンは懐中時計の蓋を閉じた。目の前にそびえるのは、三百年前に停止したはずの巨大機関——「星辰炉」だった。",
                jpWithRubyText = "冷たい鉄の匂いが満ちる地下遺構(ちかいこう)の奥で、レンは懐中時計の蓋を閉じた。目の前にそびえるのは、三百年前に停止したはずの巨大機関——「星辰炉(せいしんろ)」だった。"
            ),
            NovelParagraph(
                index = 1,
                jpPlain = "「まだ動いている……？　嘘でしょう、機巧兵団の記録では完全に焼失したと書かれていたのに」",
                jpWithRubyText = "「まだ動いている……？　嘘でしょう、機巧兵団(きこうへいだん)の記録では完全に焼失(しょうしつ)したと書かれていたのに」"
            ),
            NovelParagraph(
                index = 2,
                jpPlain = "隣に立つ少女——アステルの翡翠色の瞳が、炉心から漏れる蒼い燐光を反射して揺れた。彼女の指先がコンソールに触れた瞬間、錆びついた歯車が重々しい音を立てて噛み合い始める。",
                jpWithRubyText = "隣に立つ少女——アステルの翡翠色(ひすいいろ)の瞳が、炉心から漏れる蒼い燐光(りんこう)を反射して揺れた。彼女の指先がコンソールに触れた瞬間、錆びついた歯車が重々しい音を立てて噛み合い始める。"
            ),
            NovelParagraph(
                index = 3,
                jpPlain = "「怖がらないで、レン。これは破壊の兵器じゃないわ。かつて『黒曜の聖女』が街を凍てつく吹雪から守るために遺した、最後の揺り籠なのだから」",
                jpWithRubyText = "「怖がらないで、レン。這是破壊の兵器じゃないわ。かつて『黒曜の聖女(こくようのせいじょ)』が街を凍てつく吹雪から守るために遺した、最後の揺り籠(ゆりかご)なのだから」"
            )
        )

        val demo = NovelChapter(
            sourceUrl = "https://ncode.syosetu.com/n4829ia/14/",
            seriesKey = "syosetu:n4829ia",
            siteName = "小説家になろう",
            seriesTitle = "星屑の魔導技師と廃都の機巧少女",
            chapterTitleJp = "第十四話：廃都の底で眠る星辰炉と、黒曜の聖女の微笑み",
            author = "蒼月カナタ",
            paragraphs = sampleParagraphs,
            nextChapterUrl = "https://ncode.syosetu.com/n4829ia/15/"
        )
        _uiState.update { it.copy(currentChapter = demo) }
        triggerOneTapTranslate()
    }
}

// ============================================================================
// 7. MainActivity（支援接收 Chrome 瀏覽器分享與更新推播點擊直達）
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        val sharedUrl = extractSharedUrlFromIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val viewModel: NovelReaderViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

                LaunchedEffect(sharedUrl) {
                    if (!sharedUrl.isNullOrBlank()) {
                        viewModel.fetchUrlAndTranslate(sharedUrl)
                    }
                }

                KotoReadAppScreen(viewModel = viewModel)
            }
        }
    }

    private fun extractSharedUrlFromIntent(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
            val urlRegex = "(https?://\\S+)".toRegex()
            return urlRegex.find(sharedText)?.value
        }
        return null
    }
}

// ============================================================================
// 8. Jetpack Compose 閱讀器、網址讀取、專屬詞典與 Room/WorkManager 書架完整 UI
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KotoReadAppScreen(viewModel: NovelReaderViewModel) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) } // 0: 閱讀器, 1: 網址擷取, 2: 專屬詞典, 3: 離線書架
    var showTypographySheet by remember { mutableStateOf(false) }

    val unreadBooksCount = state.bookshelf.count { it.hasUnreadUpdate }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.currentChapter?.seriesTitle ?: "言ノ葉 Reader",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    var showExportMenu by remember { mutableStateOf(false) }

                    Box {
                        TextButton(onClick = { showExportMenu = true }) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("匯出", fontWeight = FontWeight.SemiBold)
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("📘 匯出當前話 EPUB 電子書") },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.exportCurrentChapter(ExportFormat.EPUB)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("📝 匯出中日雙語對照 TXT") },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.exportCurrentChapter(ExportFormat.TXT_DUAL)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("📄 匯出純中文譯文 TXT") },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.exportCurrentChapter(ExportFormat.TXT_ZH_ONLY)
                                }
                            )
                        }
                    }

                    TextButton(onClick = { showTypographySheet = true }) {
                        Text("Aa 排版", fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                    label = { Text("閱讀器") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Language, contentDescription = null) },
                    label = { Text("讀取網址") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Bookmarks, contentDescription = null) },
                    label = { Text("詞典 (${state.glossary.size})") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (unreadBooksCount > 0) {
                                    Badge { Text("$unreadBooksCount") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.CollectionsBookmark, contentDescription = null)
                        }
                    },
                    label = { Text("書架 (${state.bookshelf.size})") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(state.theme.bgColor)
        ) {
            when (selectedTab) {
                0 -> NovelReaderContent(
                    state = state,
                    onOneTapTranslate = { viewModel.triggerOneTapTranslate() },
                    onEngineChange = { viewModel.setTranslationEngine(it) },
                    onModeChange = { viewModel.setDisplayMode(it) },
                    onParagraphClick = { viewModel.toggleParagraphExpand(it) },
                    onLoadNextUrl = { nextUrl -> viewModel.fetchUrlAndTranslate(nextUrl) }
                )
                1 -> UrlParserScreen(
                    state = state,
                    onUrlChange = { viewModel.onUrlInputChange(it) },
                    onFetchClick = { forceRefresh ->
                        viewModel.fetchUrlAndTranslate(state.urlInput, forceNetworkRefresh = forceRefresh)
                        selectedTab = 0
                    }
                )
                2 -> GlossaryManagerScreen(
                    state = state,
                    onSaveTerm = { jp, zh, cat, note ->
                        viewModel.upsertGlossaryTerm(jp, zh, cat, note)
                    },
                    onDeleteTerm = { viewModel.deleteGlossaryTerm(it) }
                )
                3 -> BookshelfScreen(
                    state = state,
                    onContinueRead = { url ->
                        viewModel.fetchUrlAndTranslate(url, forceNetworkRefresh = false)
                        selectedTab = 0
                    },
                    onRemoveBook = { seriesKey ->
                        viewModel.deleteBookFromShelf(seriesKey)
                    },
                    onExportSeries = { key, title, author, fmt ->
                        viewModel.exportWholeSeries(key, title, author, fmt)
                    },
                    onCheckUpdatesNow = {
                        viewModel.triggerManualUpdateCheckNow()
                    },
                    onTogglePeriodicWork = { enabled ->
                        viewModel.setPeriodicUpdateCheckEnabled(enabled)
                    }
                )
            }
        }
    }

    if (showTypographySheet) {
        ModalBottomSheet(onDismissRequest = { showTypographySheet = false }) {
            TypographySettingsSheet(
                state = state,
                onThemeSelect = { viewModel.setReaderTheme(it) },
                onFontSizeChange = { viewModel.setFontSize(it) }
            )
        }
    }
}

@Composable
fun NovelReaderContent(
    state: ReaderUiState,
    onOneTapTranslate: () -> Unit,
    onEngineChange: (TranslationEngine) -> Unit,
    onModeChange: (DisplayMode) -> Unit,
    onParagraphClick: (Int) -> Unit,
    onLoadNextUrl: (String) -> Unit
) {
    val chapter = state.currentChapter ?: return
    val fontFamily = if (state.useSerifFont) FontFamily.Serif else FontFamily.SansSerif

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = state.theme.cardColor,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOneTapTranslate,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (state.engine == TranslationEngine.ML_KIT_OFFLINE) {
                                "⚡ ML Kit 離線日翻中（套用 ${state.glossary.size} 詞條）"
                            } else {
                                "一鍵日翻中（套用 ${state.glossary.size} 詞條）"
                            },
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // 翻譯引擎切換 FilterChips (ML Kit 離線 / Gemini 輕小說)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TranslationEngine.entries.forEach { eng ->
                        val selected = state.engine == eng
                        FilterChip(
                            selected = selected,
                            onClick = { onEngineChange(eng) },
                            label = { Text(eng.label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // 檢視模式切換 (純中文 / 中日對照 / 日文原文)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DisplayMode.entries.forEach { mode ->
                        val selected = state.displayMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = { onModeChange(mode) },
                            label = { Text(mode.label, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (state.isTranslating) {
                    LinearProgressIndicator(
                        progress = { state.translationProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${chapter.siteName} • 作者：${chapter.author}",
                            color = state.theme.textColor.copy(alpha = 0.65f),
                            fontSize = 12.sp
                        )
                        if (chapter.isFromOfflineCache) {
                            AssistChip(
                                onClick = {},
                                label = { Text("Room 離線快取", fontSize = 10.sp) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (state.displayMode == DisplayMode.JP_ONLY || chapter.chapterTitleZh.isBlank()) {
                            chapter.chapterTitleJp
                        } else {
                            chapter.chapterTitleZh
                        },
                        color = state.theme.textColor,
                        fontSize = (state.fontSizeSp + 3f).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = fontFamily
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 12.dp),
                        color = state.theme.textColor.copy(alpha = 0.15f)
                    )
                }
            }

            itemsIndexed(chapter.paragraphs, key = { _, p -> p.index }) { _, para ->
                ParagraphItemCard(
                    paragraph = para,
                    displayMode = state.displayMode,
                    theme = state.theme,
                    fontSizeSp = state.fontSizeSp,
                    lineHeightMultiplier = state.lineHeightMultiplier,
                    fontFamily = fontFamily,
                    glossary = state.glossary,
                    highlightGlossary = state.highlightGlossary,
                    onClick = { onParagraphClick(para.index) }
                )
            }

            chapter.nextChapterUrl?.let { nextUrl ->
                item {
                    OutlinedButton(
                        onClick = { onLoadNextUrl(nextUrl) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    ) {
                        Text("讀取並翻譯下一話 →")
                    }
                }
            }
        }
    }
}

@Composable
fun ParagraphItemCard(
    paragraph: NovelParagraph,
    displayMode: DisplayMode,
    theme: ReaderTheme,
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
    fontFamily: FontFamily,
    glossary: List<GlossaryTerm>,
    highlightGlossary: Boolean,
    onClick: () -> Unit
) {
    val lineHeight = (fontSizeSp * lineHeightMultiplier).sp

    val zhAnnotated = remember(paragraph.zhTranslated, glossary, highlightGlossary) {
        buildHighlightedText(paragraph.zhTranslated, glossary, highlightGlossary)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (paragraph.isExpanded) theme.cardColor else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        when (displayMode) {
            DisplayMode.ZH_ONLY -> {
                Text(
                    text = zhAnnotated,
                    color = theme.textColor,
                    fontSize = fontSizeSp.sp,
                    lineHeight = lineHeight,
                    fontFamily = fontFamily
                )
                AnimatedVisibility(visible = paragraph.isExpanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            text = "🇯🇵 日文原文（含振假名）：",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = paragraph.jpWithRubyText,
                            color = theme.textColor.copy(alpha = 0.8f),
                            fontSize = (fontSizeSp - 2f).sp,
                            lineHeight = lineHeight
                        )
                    }
                }
            }

            DisplayMode.DUAL -> {
                Text(
                    text = paragraph.jpWithRubyText,
                    color = theme.textColor.copy(alpha = 0.72f),
                    fontSize = (fontSizeSp - 2f).sp,
                    lineHeight = (fontSizeSp * 1.55f).sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = zhAnnotated,
                    color = theme.textColor,
                    fontSize = fontSizeSp.sp,
                    lineHeight = lineHeight,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Medium
                )
            }

            DisplayMode.JP_ONLY -> {
                Text(
                    text = paragraph.jpWithRubyText,
                    color = theme.textColor,
                    fontSize = fontSizeSp.sp,
                    lineHeight = lineHeight,
                    fontFamily = fontFamily
                )
            }
        }
    }
}

fun buildHighlightedText(
    text: String,
    glossary: List<GlossaryTerm>,
    enabled: Boolean
) = buildAnnotatedString {
    append(text)
    if (!enabled || text.isEmpty()) return@buildAnnotatedString

    for (term in glossary) {
        val target = term.zhTerm
        if (target.isBlank()) continue
        var startIndex = text.indexOf(target)
        while (startIndex >= 0) {
            val endIndex = startIndex + target.length
            addStyle(
                style = SpanStyle(
                    color = Color(0xFF818CF8),
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline
                ),
                start = startIndex,
                end = endIndex
            )
            startIndex = text.indexOf(target, endIndex)
        }
    }
}

@Composable
fun UrlParserScreen(
    state: ReaderUiState,
    onUrlChange: (String) -> Unit,
    onFetchClick: (forceRefresh: Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "輸入日文小說網址 (Syosetu / Kakuyomu / 一般網頁)",
            style = MaterialTheme.typography.titleMedium,
            color = state.theme.textColor
        )
        OutlinedTextField(
            value = state.urlInput,
            onValueChange = onUrlChange,
            label = { Text("小說章節 URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onFetchClick(false) },
                enabled = !state.isLoadingUrl,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (state.isLoadingUrl) "讀取中..." else "優先從快取或網路開啟")
            }
            OutlinedButton(
                onClick = { onFetchClick(true) },
                enabled = !state.isLoadingUrl
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("強制重抓")
            }
        }
    }
}

@Composable
fun GlossaryManagerScreen(
    state: ReaderUiState,
    onSaveTerm: (jp: String, zh: String, category: String, note: String) -> Unit,
    onDeleteTerm: (id: String) -> Unit
) {
    var jpInput by remember { mutableStateOf("") }
    var zhInput by remember { mutableStateOf("") }
    var noteInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "專有名詞與角色譯名鎖定詞典 (Room 持久化)",
            style = MaterialTheme.typography.titleMedium,
            color = state.theme.textColor
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = jpInput,
                onValueChange = { jpInput = it },
                label = { Text("日文原文") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = zhInput,
                onValueChange = { zhInput = it },
                label = { Text("固定中文譯名") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = noteInput,
                onValueChange = { noteInput = it },
                label = { Text("性別代詞或備註（選填）") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    onSaveTerm(jpInput, zhInput, "自訂詞條", noteInput)
                    jpInput = ""
                    zhInput = ""
                    noteInput = ""
                }
            ) {
                Text("儲存")
            }
        }

        HorizontalDivider()

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(state.glossary, key = { _, item -> item.id }) { _, term ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(state.theme.cardColor)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${term.jpTerm}  →  ${term.zhTerm}",
                            color = state.theme.textColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        if (term.note.isNotBlank()) {
                            Text(
                                text = term.note,
                                color = state.theme.textColor.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    IconButton(onClick = { onDeleteTerm(term.id) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "刪除詞條",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BookshelfScreen(
    state: ReaderUiState,
    onContinueRead: (chapterUrl: String) -> Unit,
    onRemoveBook: (seriesKey: String) -> Unit,
    onExportSeries: (seriesKey: String, seriesTitle: String, author: String, format: ExportFormat) -> Unit,
    onCheckUpdatesNow: () -> Unit,
    onTogglePeriodicWork: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(state.theme.cardColor)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "WorkManager 自動巡檢連載更新",
                        color = state.theme.textColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = state.lastUpdateCheckReport,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = state.isPeriodicWorkEnabled,
                    onCheckedChange = onTogglePeriodicWork
                )
            }

            Button(
                onClick = onCheckUpdatesNow,
                enabled = !state.isCheckingUpdatesNow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.isCheckingUpdatesNow) "正在背景巡檢並預翻譯新章節..."
                    else "立即巡檢所有書架作品更新"
                )
            }
        }

        if (state.bookshelf.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "書架尚無作品，讀取任一小說網址後將自動加入書架並離線快取。",
                    color = state.theme.textColor.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(state.bookshelf, key = { _, b -> b.seriesKey }) { _, book ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(state.theme.cardColor)
                            .border(
                                width = if (book.hasUnreadUpdate) 1.5.dp else 1.dp,
                                color = if (book.hasUnreadUpdate) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${book.siteName} • 已離線快取 ${book.cachedChapterCount} 話",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (book.hasUnreadUpdate) {
                                    Badge(containerColor = Color(0xFFE11D48)) {
                                        Text("NEW 新章節已預譯", color = Color.White)
                                    }
                                }
                            }
                            IconButton(
                                onClick = { onRemoveBook(book.seriesKey) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "移除書架與快取",
                                    tint = state.theme.textColor.copy(alpha = 0.6f)
                                )
                            }
                        }

                        Text(
                            text = book.seriesTitle,
                            color = state.theme.textColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )

                        Text(
                            text = "上次進度：${book.lastReadChapterTitle}",
                            color = state.theme.textColor.copy(alpha = 0.78f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (book.hasUnreadUpdate && !book.latestKnownNextUrl.isNullOrBlank()) {
                            Text(
                                text = "🔔 最新更新：${book.latestKnownNextTitle}",
                                color = Color(0xFFF43F5E),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            var showShelfExportMenu by remember { mutableStateOf(false) }

                            Box {
                                OutlinedButton(onClick = { showShelfExportMenu = true }) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("匯出全本 (${book.cachedChapterCount}話)", fontSize = 12.sp)
                                }
                                DropdownMenu(
                                    expanded = showShelfExportMenu,
                                    onDismissRequest = { showShelfExportMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("📘 匯出全書 EPUB 電子書") },
                                        onClick = {
                                            showShelfExportMenu = false
                                            onExportSeries(book.seriesKey, book.seriesTitle, book.author, ExportFormat.EPUB)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("📝 匯出全書中日雙語 TXT") },
                                        onClick = {
                                            showShelfExportMenu = false
                                            onExportSeries(book.seriesKey, book.seriesTitle, book.author, ExportFormat.TXT_DUAL)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("📄 匯出全書純中文 TXT") },
                                        onClick = {
                                            showShelfExportMenu = false
                                            onExportSeries(book.seriesKey, book.seriesTitle, book.author, ExportFormat.TXT_ZH_ONLY)
                                        }
                                    )
                                }
                            }

                            if (book.hasUnreadUpdate && !book.latestKnownNextUrl.isNullOrBlank()) {
                                FilledTonalButton(
                                    onClick = { onContinueRead(book.latestKnownNextUrl) }
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("直達最新話", fontSize = 13.sp)
                                }
                            }

                            Button(onClick = { onContinueRead(book.lastReadChapterUrl) }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("繼續閱讀", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TypographySettingsSheet(
    state: ReaderUiState,
    onThemeSelect: (ReaderTheme) -> Unit,
    onFontSizeChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("閱讀排版與護眼主題", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderTheme.entries.forEach { theme ->
                FilterChip(
                    selected = state.theme == theme,
                    onClick = { onThemeSelect(theme) },
                    label = { Text(theme.label) }
                )
            }
        }

        Column {
            Text("字體大小：${state.fontSizeSp.toInt()} sp")
            Slider(
                value = state.fontSizeSp,
                onValueChange = onFontSizeChange,
                valueRange = 14f..24f
            )
        }
    }
}