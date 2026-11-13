package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertSession(session: ScanSessionEntity)

  @Query("SELECT * FROM scan_sessions ORDER BY createdAtMillis DESC")
  fun observeAllSessions(): Flow<List<ScanSessionEntity>>

  @Query("SELECT * FROM scan_sessions WHERE sessionId = :sessionId LIMIT 1")
  fun observeSession(sessionId: String): Flow<ScanSessionEntity?>

  @Query("SELECT * FROM scan_sessions WHERE sessionId = :sessionId LIMIT 1")
  suspend fun getSession(sessionId: String): ScanSessionEntity?

  @Query("SELECT * FROM scan_sessions ORDER BY createdAtMillis DESC LIMIT 1")
  suspend fun getLatestSession(): ScanSessionEntity?

  @Query("DELETE FROM scan_sessions WHERE sessionId = :sessionId")
  suspend fun deleteSession(sessionId: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertResults(results: List<ScanResultEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertResult(result: ScanResultEntity)

  @Query("SELECT * FROM scan_results WHERE sessionId = :sessionId")
  fun observeResultsForSession(sessionId: String): Flow<List<ScanResultEntity>>

  @Query("SELECT * FROM scan_results WHERE sessionId = :sessionId")
  suspend fun getResultsForSession(sessionId: String): List<ScanResultEntity>

  @Query("DELETE FROM scan_results WHERE sessionId = :sessionId")
  suspend fun deleteResultsForSession(sessionId: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertLog(log: ScanLogEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertLogs(logs: List<ScanLogEntity>)

  @Query("SELECT * FROM scan_logs WHERE sessionId = :sessionId ORDER BY id DESC LIMIT 1500")
  fun observeLogsForSession(sessionId: String): Flow<List<ScanLogEntity>>

  @Query("SELECT * FROM scan_logs WHERE sessionId = :sessionId ORDER BY id ASC")
  suspend fun getAllLogsForSession(sessionId: String): List<ScanLogEntity>

  @Query("DELETE FROM scan_logs WHERE sessionId = :sessionId")
  suspend fun deleteLogsForSession(sessionId: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveAwsRangeCache(cache: AwsRangeCacheEntity)

  @Query("SELECT * FROM aws_range_cache WHERE id = 1 LIMIT 1")
  suspend fun getAwsRangeCache(): AwsRangeCacheEntity?
}
