package com.openshield.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ─── Entity'ler ───────────────────────────────────────────────────────────────

@Entity(tableName = "spam_numbers")
data class SpamNumberEntity(
    @PrimaryKey val number: String,
    val label: String = "",
    val isUserAdded: Boolean = true,
    val reportCount: Int = 1,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "whitelist")
data class WhitelistEntity(
    @PrimaryKey val number: String,
    val name: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "blocked_log")
data class BlockedLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val reason: String,
    val score: Float,
    val blockedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "pending_review")
data class PendingReviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val reason: String,
    val score: Float,
    val receivedAt: Long = System.currentTimeMillis()
)

/**
 * Wi-Fi yokken veya gönderim başarısız olduğunda biriken raporlar.
 * voteType: "spam" | "not_spam"
 * retryCount: kaç kez denendi (exponential backoff için)
 */
@Entity(tableName = "pending_reports")
data class PendingReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val numberHash: String,
    val number: String = "",             // düz numara — admin panelinde görünmesi için
    val triggeredRules: String,          // JSON array string
    val voteType: String = "spam",       // "spam" | "not_spam"
    val retryCount: Int = 0,
    val nextRetryAt: Long = 0L,          // epoch ms — bu zamandan önce deneme
    val createdAt: Long = System.currentTimeMillis()
)

// ─── DAO'lar ──────────────────────────────────────────────────────────────────

@Dao
interface SpamNumberDao {
    @Query("SELECT * FROM spam_numbers ORDER BY addedAt DESC")
    fun getAllFlow(): Flow<List<SpamNumberEntity>>

    @Query("SELECT * FROM spam_numbers WHERE isUserAdded = 1 ORDER BY addedAt DESC")
    fun getUserAddedFlow(): Flow<List<SpamNumberEntity>>

    @Query("DELETE FROM spam_numbers WHERE isUserAdded = 0")
    suspend fun deleteAllBundled()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SpamNumberEntity>)

    @Query("SELECT * FROM spam_numbers WHERE number = :number LIMIT 1")
    suspend fun findByNumber(number: String): SpamNumberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SpamNumberEntity)

    @Query("DELETE FROM spam_numbers WHERE number = :number")
    suspend fun deleteByNumber(number: String)

    @Query("SELECT COUNT(*) FROM spam_numbers")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM spam_numbers WHERE isUserAdded = 0")
    suspend fun communityCount(): Int

    @Query("DELETE FROM spam_numbers WHERE isUserAdded = 1")
    suspend fun deleteAllUserSpam()

    suspend fun insertCommunityHash(hash: String) {
        insert(SpamNumberEntity(number = hash, label = "Topluluk", isUserAdded = false))
    }
}

@Dao
interface WhitelistDao {
    @Query("SELECT * FROM whitelist ORDER BY addedAt DESC")
    fun getAllFlow(): Flow<List<WhitelistEntity>>

    @Query("SELECT * FROM whitelist WHERE number = :number LIMIT 1")
    suspend fun findByNumber(number: String): WhitelistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WhitelistEntity)

    @Query("DELETE FROM whitelist WHERE number = :number")
    suspend fun deleteByNumber(number: String)

    @Query("DELETE FROM whitelist")
    suspend fun clearAll()
}

@Dao
interface BlockedLogDao {
    @Query("SELECT * FROM blocked_log ORDER BY blockedAt DESC LIMIT 100")
    fun getRecentFlow(): Flow<List<BlockedLogEntity>>

    @Insert
    suspend fun insert(entity: BlockedLogEntity)

    @Query("SELECT COUNT(*) FROM blocked_log")
    suspend fun totalCount(): Int

    @Query("DELETE FROM blocked_log")
    suspend fun clearAll()
}

@Dao
interface PendingReviewDao {
    @Query("SELECT * FROM pending_review ORDER BY receivedAt ASC")
    fun getAllFlow(): Flow<List<PendingReviewEntity>>

    @Query("SELECT * FROM pending_review ORDER BY receivedAt ASC")
    suspend fun getAll(): List<PendingReviewEntity>

    @Insert
    suspend fun insert(entity: PendingReviewEntity)

    @Query("DELETE FROM pending_review WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM pending_review")
    suspend fun count(): Int
}

@Dao
interface PendingReportDao {
    // Sadece retry zamanı gelmiş olanları getir
    @Query("SELECT * FROM pending_reports WHERE nextRetryAt <= :now ORDER BY createdAt ASC")
    suspend fun getDue(now: Long = System.currentTimeMillis()): List<PendingReportEntity>

    @Query("SELECT COUNT(*) FROM pending_reports")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: PendingReportEntity)

    @Query("DELETE FROM pending_reports WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Update
    suspend fun update(entity: PendingReportEntity)
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [
        SpamNumberEntity::class,
        WhitelistEntity::class,
        BlockedLogEntity::class,
        PendingReviewEntity::class,
        PendingReportEntity::class,
    ],
    version = 4,
    exportSchema = false
)
abstract class SpamDatabase : RoomDatabase() {
    abstract fun spamNumberDao(): SpamNumberDao
    abstract fun whitelistDao(): WhitelistDao
    abstract fun blockLogDao(): BlockedLogDao
    abstract fun pendingReviewDao(): PendingReviewDao
    abstract fun pendingReportDao(): PendingReportDao

    companion object {
        @Volatile private var INSTANCE: SpamDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_review (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sender TEXT NOT NULL, reason TEXT NOT NULL,
                        score REAL NOT NULL, receivedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_reports (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        numberHash TEXT NOT NULL,
                        number TEXT NOT NULL DEFAULT '',
                        triggeredRules TEXT NOT NULL,
                        voteType TEXT NOT NULL DEFAULT 'spam',
                        retryCount INTEGER NOT NULL DEFAULT 0,
                        nextRetryAt INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        // v3 → v4: community_reports silindi, spam_numbers tablosuna yeni alanlar eklendi
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS community_reports")
                if (!hasColumn(db, "spam_numbers", "isUserAdded")) {
                    db.execSQL("ALTER TABLE spam_numbers ADD COLUMN isUserAdded INTEGER NOT NULL DEFAULT 1")
                }
                if (!hasColumn(db, "spam_numbers", "reportCount")) {
                    db.execSQL("ALTER TABLE spam_numbers ADD COLUMN reportCount INTEGER NOT NULL DEFAULT 1")
                }
            }
        }

        private fun hasColumn(db: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex == -1) return false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex).equals(columnName, ignoreCase = true)) {
                        return true
                    }
                }
            }
            return false
        }

        fun getInstance(context: Context): SpamDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    SpamDatabase::class.java,
                    "openshield.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
