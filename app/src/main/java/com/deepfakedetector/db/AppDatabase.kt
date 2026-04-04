package com.deepfakedetector.db

import androidx.room.*
import com.deepfakedetector.data.*
import kotlinx.coroutines.flow.Flow

// ─── Entités Room ─────────────────────────────────────────────────────────────

@Entity(tableName = "analysis_results")
data class AnalysisResultEntity(
    @PrimaryKey val id: String,
    val videoUri: String,
    val videoHash: String,
    val globalScore: Float,
    val confidence: Float,
    val verdict: String,             // VerdictLevel.name
    val reliability: String,         // ReliabilityLevel.name
    val mode: String,                // AnalysisMode.name
    val explanation: String,
    val keyFindings: String,         // JSON array
    val warnings: String,            // JSON array
    val pixelScore: Float?,
    val audioScore: Float?,
    val faceScore: Float?,
    val physiologicalScore: Float?,
    val temporalScore: Float?,
    val metadataScore: Float?,
    val durationMs: Long,
    val analyzedAt: Long,            // timestamp
    val thumbnailPath: String?
)

@Entity(tableName = "community_reports")
data class CommunityReportEntity(
    @PrimaryKey val id: String,
    val videoHash: String,
    val fakeVotes: Int,
    val realVotes: Int,
    val reportCount: Int,
    val sources: String,             // JSON array de strings
    val lastUpdated: Long
)

@Entity(tableName = "backtest_runs")
data class BacktestRunEntity(
    @PrimaryKey val id: String,
    val accuracy: Float,
    val precision: Float,
    val recall: Float,
    val f1Score: Float,
    val auc: Float,
    val optimalThreshold: Float,
    val truePositives: Int,
    val trueNegatives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val totalVideos: Int,
    val runAt: Long,
    val reportText: String
)

// ─── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface AnalysisDao {

    @Query("SELECT * FROM analysis_results ORDER BY analyzedAt DESC")
    fun getAllResults(): Flow<List<AnalysisResultEntity>>

    @Query("SELECT * FROM analysis_results ORDER BY analyzedAt DESC LIMIT :limit")
    fun getRecentResults(limit: Int = 20): Flow<List<AnalysisResultEntity>>

    @Query("SELECT * FROM analysis_results WHERE id = :id")
    suspend fun getById(id: String): AnalysisResultEntity?

    @Query("SELECT * FROM analysis_results WHERE videoHash = :hash LIMIT 1")
    suspend fun getByVideoHash(hash: String): AnalysisResultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: AnalysisResultEntity)

    @Delete
    suspend fun delete(result: AnalysisResultEntity)

    @Query("DELETE FROM analysis_results WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM analysis_results")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM analysis_results")
    suspend fun count(): Int

    @Query("""
        SELECT AVG(globalScore) FROM analysis_results 
        WHERE analyzedAt > :since
    """)
    suspend fun averageScoreSince(since: Long): Float?

    // Stats rapides pour l'écran historique
    @Query("""
        SELECT verdict, COUNT(*) as count 
        FROM analysis_results 
        GROUP BY verdict
    """)
    suspend fun verdictStats(): List<VerdictCount>

    @Query("""
        SELECT * FROM analysis_results 
        WHERE videoHash = :hash 
        ORDER BY analyzedAt DESC 
        LIMIT 5
    """)
    suspend fun getHistoryForVideo(hash: String): List<AnalysisResultEntity>
}

data class VerdictCount(
    val verdict: String,
    val count: Int
)

@Dao
interface CommunityDao {

    @Query("SELECT * FROM community_reports WHERE videoHash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): CommunityReportEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: CommunityReportEntity)

    @Query("SELECT * FROM community_reports ORDER BY reportCount DESC LIMIT 50")
    fun getMostReported(): Flow<List<CommunityReportEntity>>

    @Query("UPDATE community_reports SET fakeVotes = fakeVotes + 1, lastUpdated = :now WHERE videoHash = :hash")
    suspend fun incrementFakeVotes(hash: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE community_reports SET realVotes = realVotes + 1, lastUpdated = :now WHERE videoHash = :hash")
    suspend fun incrementRealVotes(hash: String, now: Long = System.currentTimeMillis())
}

@Dao
interface BacktestDao {

    @Query("SELECT * FROM backtest_runs ORDER BY runAt DESC")
    fun getAllRuns(): Flow<List<BacktestRunEntity>>

    @Query("SELECT * FROM backtest_runs ORDER BY runAt DESC LIMIT 1")
    suspend fun getLatestRun(): BacktestRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: BacktestRunEntity)

    @Query("DELETE FROM backtest_runs")
    suspend fun deleteAll()
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [
        AnalysisResultEntity::class,
        CommunityReportEntity::class,
        BacktestRunEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun analysisDao(): AnalysisDao
    abstract fun communityDao(): CommunityDao
    abstract fun backtestDao(): BacktestDao

    companion object {
        const val DATABASE_NAME = "deepfake_detector.db"
    }
}
