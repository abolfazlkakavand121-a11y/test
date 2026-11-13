package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
  @PrimaryKey val sessionId: String,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
  val scanMode: String,
  val ipFamilyPreference: String,
  val scanPreset: String,
  val awsSyncToken: String,
  val awsCreateDate: String,
  val awsIsFromCache: Boolean,
  val vlessRedactedSummary: String,
  val settingsSnapshotJson: String,
  val phase: String,
  val totalInputs: Int,
  val validInputs: Int,
  val duplicatesRemoved: Int,
  val invalidInputsCount: Int,
  val phase1CompletedCount: Int,
  val tcpPassCount: Int,
  val tcpFailCount: Int,
  val tlsPassCount: Int,
  val tlsFailCount: Int,
  val wsPassCount: Int,
  val wsFailCount: Int,
  val skippedCount: Int,
  val phase2TargetCount: Int,
  val phase2CompletedCount: Int,
  val tunnelPassCount: Int,
  val tunnelFailCount: Int,
  val inconclusiveCount: Int,
  val ratioNote: String,
)

@Entity(
  tableName = "scan_results",
  indices = [Index(value = ["sessionId"]), Index(value = ["sessionId", "ip"], unique = true)],
)
data class ScanResultEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionId: String,
  val ip: String,
  val ipVersion: String,
  val sourceCidr: String,
  val isManual: Boolean,
  val port: Int,
  val sniUsed: String,
  val hostUsed: String,
  val overallStatus: String,
  val icmpPingMs: Long?,
  val tcpStatus: String,
  val tcpMs: Long?,
  val tlsStatus: String,
  val tlsMs: Long?,
  val wsStatus: String,
  val wsMs: Long?,
  val wsHttpCode: Int?,
  val tunnelStatus: String,
  val tunnelAttempts: Int,
  val tunnelSuccesses: Int,
  val tunnelTimeoutsOrDrops: Int,
  val tunnelMedianMs: Long?,
  val tunnelMinMs: Long?,
  val tunnelMaxMs: Long?,
  val externalIpObserved: String?,
  val downloadKbps: Double?,
  val stabilityScore: Double,
  val failureCause: String,
  val detailMessageFa: String,
  val detailMessageEn: String,
  val phase1Qualified: Boolean,
  val updatedAtMillis: Long,
)

@Entity(tableName = "scan_logs", indices = [Index(value = ["sessionId"])])
data class ScanLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionId: String,
  val timestampMillis: Long,
  val ip: String,
  val stage: String,
  val eventCode: String,
  val level: String,
  val durationMs: Long?,
  val messageFa: String,
  val messageEn: String,
)

@Entity(tableName = "aws_range_cache")
data class AwsRangeCacheEntity(
  @PrimaryKey val id: Int = 1,
  val syncToken: String,
  val createDate: String,
  val fetchedAtMillis: Long,
  val rawPayloadJson: String,
  val secondaryDiffNote: String,
)
