package com.example.scanner

import com.example.core.network.AwsIpRangeRepository
import com.example.core.network.CidrMath
import com.example.core.network.Phase1ProbeEngine
import com.example.core.network.VlessParser
import com.example.core.network.XrayTunnelRunner
import com.example.data.local.ReportExporter
import com.example.data.local.ScanDao
import com.example.data.local.ScanLogEntity
import com.example.data.local.ScanResultEntity
import com.example.data.local.ScanSessionEntity
import com.example.data.local.SettingsDataStore
import com.example.domain.model.AwsIpRangesSnapshot
import com.example.domain.model.FailureCause
import com.example.domain.model.IpCandidate
import com.example.domain.model.LogLevel
import com.example.domain.model.ManualParseReport
import com.example.domain.model.OverallIpStatus
import com.example.domain.model.Phase2SelectionMode
import com.example.domain.model.ScanMode
import com.example.domain.model.ScanPhase
import com.example.domain.model.ScanSettings
import com.example.domain.model.SubStepStatus
import com.example.domain.model.VlessConfig
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class LiveScanUiState(
  val activeSessionId: String? = null,
  val phase: ScanPhase = ScanPhase.IDLE,
  val totalPhase1Targets: Int = 0,
  val processedPhase1: Int = 0,
  val totalPhase2Targets: Int = 0,
  val processedPhase2: Int = 0,
  val tcpPassCount: Int = 0,
  val tlsPassCount: Int = 0,
  val wsPassCount: Int = 0,
  val failCount: Int = 0,
  val skippedCount: Int = 0,
  val tunnelPassCount: Int = 0,
  val tunnelFailCount: Int = 0,
  val inconclusiveCount: Int = 0,
  val currentSpeedIpsPerSec: Float = 0f,
  val ratioNote: String = "",
  val possibleIspDisruptionWarning: Boolean = false,
  val lastErrorBanner: String? = null,
  val qualifiedPhase1Count: Int = 0,
)

class ScanCoordinator(
  private val scanDao: ScanDao,
  private val settingsStore: SettingsDataStore,
  private val awsRepository: AwsIpRangeRepository,
  private val phase1Engine: Phase1ProbeEngine,
  private val xrayRunner: XrayTunnelRunner,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = Json { encodeDefaults = true }

  private val _liveState = MutableStateFlow(LiveScanUiState())
  val liveState: StateFlow<LiveScanUiState> = _liveState.asStateFlow()

  private var activeJob: Job? = null
  private val isPausedFlag = AtomicBoolean(false)
  private val isStoppedFlag = AtomicBoolean(false)
  private val sessionMutex = Mutex()

  private var onServiceNotificationUpdate: ((LiveScanUiState) -> Unit)? = null
  private var onServiceFinished: (() -> Unit)? = null

  fun attachServiceCallbacks(
    onUpdate: (LiveScanUiState) -> Unit,
    onFinish: () -> Unit,
  ) {
    onServiceNotificationUpdate = onUpdate
    onServiceFinished = onFinish
  }

  fun detachServiceCallbacks() {
    onServiceNotificationUpdate = null
    onServiceFinished = null
  }

  suspend fun restoreLastSessionIfIdle() {
    if (_liveState.value.activeSessionId != null) return
    val savedId = settingsStore.activeSessionIdFlow.first()
    val session =
      if (!savedId.isNullOrBlank()) scanDao.getSession(savedId) else scanDao.getLatestSession()
    if (session != null) {
      val results = scanDao.getResultsForSession(session.sessionId)
      val qualified = results.count { it.phase1Qualified }
      val restoredPhase =
        when (session.phase) {
          ScanPhase.PHASE1_PROBING.name,
          ScanPhase.PHASE2_TUNNEL.name,
          ScanPhase.PAUSED.name -> ScanPhase.PAUSED
          ScanPhase.PHASE1_COMPLETED.name -> ScanPhase.PHASE1_COMPLETED
          ScanPhase.COMPLETED.name -> ScanPhase.COMPLETED
          ScanPhase.STOPPED.name -> ScanPhase.STOPPED
          else -> ScanPhase.IDLE
        }
      _liveState.value =
        LiveScanUiState(
          activeSessionId = session.sessionId,
          phase = restoredPhase,
          totalPhase1Targets = session.validInputs,
          processedPhase1 = session.phase1CompletedCount,
          totalPhase2Targets = session.phase2TargetCount,
          processedPhase2 = session.phase2CompletedCount,
          tcpPassCount = session.tcpPassCount,
          tlsPassCount = session.tlsPassCount,
          wsPassCount = session.wsPassCount,
          failCount = session.tcpFailCount + session.tlsFailCount + session.wsFailCount,
          skippedCount = session.skippedCount,
          tunnelPassCount = session.tunnelPassCount,
          tunnelFailCount = session.tunnelFailCount,
          inconclusiveCount = session.inconclusiveCount,
          ratioNote = session.ratioNote,
          qualifiedPhase1Count = qualified,
        )
    }
  }

  fun startPhase1Scan(
    settings: ScanSettings,
    vlessConfig: VlessConfig?,
    awsSnapshot: AwsIpRangesSnapshot?,
    manualReport: ManualParseReport?,
  ) {
    activeJob?.cancel()
    isPausedFlag.set(false)
    isStoppedFlag.set(false)

    activeJob =
      scope.launch {
        try {
          val sessionId = UUID.randomUUID().toString().take(12)
          val now = System.currentTimeMillis()

          val candidates: List<IpCandidate>
          val totalInputs: Int
          val validInputs: Int
          val dupRemoved: Int
          val invalidCount: Int
          val ratioNote: String

          if (settings.scanMode == ScanMode.AUTO_CLOUDFRONT) {
            val snap =
              awsSnapshot
                ?: awsRepository.fetchLatestOrCached(forceRefresh = false).getOrNull()
                ?: throw IllegalStateException("فهرست رنج‌های رسمی AWS هنوز دریافت نشده است")
            val autoReport =
              CidrMath.sampleAutoCloudFront(
                v4Prefixes = snap.globalEdgeV4,
                v6Prefixes = snap.globalEdgeV6,
                familyPref = settings.ipFamilyPreference,
                requestedCount = settings.autoSampleCount,
              )
            candidates = autoReport.candidates
            totalInputs = candidates.size
            validInputs = candidates.size
            dupRemoved = 0
            invalidCount = 0
            ratioNote =
              if (settings.language.code == "fa") autoReport.ratioDescriptionFa
              else autoReport.ratioDescriptionEn
          } else {
            val report =
              manualReport
                ?: throw IllegalStateException("هیچ آدرس IP یا CIDR معتبری وارد نشده است")
            if (report.uniqueIps.isEmpty()) {
              throw IllegalStateException("هیچ IP معتبری برای اسکن یافت نشد")
            }
            candidates = report.uniqueIps
            totalInputs = report.totalInputEntries
            validInputs = report.uniqueIps.size
            dupRemoved = report.duplicatesRemoved
            invalidCount = report.errors.size
            ratioNote =
              "دستی: ${candidates.count { it.ipVersion.name == "IPV4" }} IPv4 / ${candidates.count { it.ipVersion.name == "IPV6" }} IPv6"
          }

          val vlessSummary = vlessConfig?.redactedSummary().orEmpty()
          val session =
            ScanSessionEntity(
              sessionId = sessionId,
              createdAtMillis = now,
              updatedAtMillis = now,
              scanMode = settings.scanMode.name,
              ipFamilyPreference = settings.ipFamilyPreference.name,
              scanPreset = settings.scanPreset.name,
              awsSyncToken = awsSnapshot?.syncToken.orEmpty(),
              awsCreateDate = awsSnapshot?.createDate.orEmpty(),
              awsIsFromCache = awsSnapshot?.isFromCache ?: false,
              vlessRedactedSummary = vlessSummary,
              settingsSnapshotJson = ReportExporter.redactSensitiveText(json.encodeToString(settings)),
              phase = ScanPhase.PHASE1_PROBING.name,
              totalInputs = totalInputs,
              validInputs = validInputs,
              duplicatesRemoved = dupRemoved,
              invalidInputsCount = invalidCount,
              phase1CompletedCount = 0,
              tcpPassCount = 0,
              tcpFailCount = 0,
              tlsPassCount = 0,
              tlsFailCount = 0,
              wsPassCount = 0,
              wsFailCount = 0,
              skippedCount = 0,
              phase2TargetCount = 0,
              phase2CompletedCount = 0,
              tunnelPassCount = 0,
              tunnelFailCount = 0,
              inconclusiveCount = 0,
              ratioNote = ratioNote,
            )
          scanDao.upsertSession(session)
          settingsStore.saveActiveSessionId(sessionId)

          val initialRows =
            candidates.map { cand ->
              ScanResultEntity(
                sessionId = sessionId,
                ip = cand.ip,
                ipVersion = cand.ipVersion.name,
                sourceCidr = cand.sourceCidr,
                isManual = cand.isManual,
                port = vlessConfig?.port ?: settings.defaultPort,
                sniUsed = vlessConfig?.effectiveSni.orEmpty(),
                hostUsed = vlessConfig?.wsHost.orEmpty(),
                overallStatus = OverallIpStatus.PENDING.name,
                icmpPingMs = null,
                tcpStatus = SubStepStatus.PENDING.name,
                tcpMs = null,
                tlsStatus = SubStepStatus.PENDING.name,
                tlsMs = null,
                wsStatus = SubStepStatus.PENDING.name,
                wsMs = null,
                wsHttpCode = null,
                tunnelStatus = OverallIpStatus.PENDING.name,
                tunnelAttempts = 0,
                tunnelSuccesses = 0,
                tunnelTimeoutsOrDrops = 0,
                tunnelMedianMs = null,
                tunnelMinMs = null,
                tunnelMaxMs = null,
                externalIpObserved = null,
                downloadKbps = null,
                stabilityScore = 0.0,
                failureCause = FailureCause.NONE.name,
                detailMessageFa = "در صف بررسی مرحله اول",
                detailMessageEn = "Pending Phase 1 probe",
                phase1Qualified = false,
                updatedAtMillis = now,
              )
            }
          scanDao.insertResults(initialRows)

          emitLog(
            sessionId = sessionId,
            ip = "-",
            stage = "PHASE1",
            eventCode = "SCAN_STARTED",
            level = LogLevel.INFO,
            durationMs = null,
            msgFa = "شروع مرحله اول برای $validInputs آدرس ($ratioNote)",
            msgEn = "Phase 1 started for $validInputs targets ($ratioNote)",
          )

          if (manualReport != null && manualReport.errors.isNotEmpty()) {
            for (err in manualReport.errors.take(20)) {
              emitLog(
                sessionId = sessionId,
                ip = err.rawLine.take(45),
                stage = "INPUT",
                eventCode = "INVALID_INPUT_LINE_${err.lineNumber}",
                level = LogLevel.FAIL,
                durationMs = null,
                msgFa = "خط ${err.lineNumber}: ${err.reasonFa}",
                msgEn = "Line ${err.lineNumber}: ${err.reasonEn}",
              )
            }
          }

          _liveState.value =
            LiveScanUiState(
              activeSessionId = sessionId,
              phase = ScanPhase.PHASE1_PROBING,
              totalPhase1Targets = validInputs,
              ratioNote = ratioNote,
            )
          notifyService()

          runPhase1Loop(sessionId, settings, vlessConfig)
        } catch (ce: CancellationException) {
          throw ce
        } catch (e: Exception) {
          _liveState.update { it.copy(phase = ScanPhase.IDLE, lastErrorBanner = e.message) }
          onServiceFinished?.invoke()
        }
      }
  }

  private suspend fun runPhase1Loop(
    sessionId: String,
    settings: ScanSettings,
    vlessConfig: VlessConfig?,
  ) {
    val allRows = scanDao.getResultsForSession(sessionId)
    val pendingRows = allRows.filter { it.overallStatus == OverallIpStatus.PENDING.name }
    val startTimeMs = System.currentTimeMillis()
    val processedAtomic = AtomicInteger(allRows.size - pendingRows.size)
    val netFailureStreak = AtomicInteger(0)
    val semaphore = Semaphore(settings.phase1Workers.coerceIn(1, 32))

    coroutineScope {
      pendingRows
        .map { row ->
          async {
            while (isPausedFlag.get() && !isStoppedFlag.get()) {
              delay(150L)
            }
            if (isStoppedFlag.get()) return@async

            semaphore.withPermit {
              while (isPausedFlag.get() && !isStoppedFlag.get()) {
                delay(150L)
              }
              if (isStoppedFlag.get()) return@withPermit

              val cand =
                IpCandidate(
                  ip = row.ip,
                  ipVersion = enumValueOf(row.ipVersion),
                  sourceCidr = row.sourceCidr,
                  isManual = row.isManual,
                )
              val outcome = phase1Engine.probeCandidate(cand, settings, vlessConfig)

              val updatedRow =
                row.copy(
                  port = outcome.port,
                  sniUsed = outcome.sniUsed,
                  hostUsed = outcome.hostUsed,
                  overallStatus = outcome.overallStatus.name,
                  icmpPingMs = outcome.icmpPingMs,
                  tcpStatus = outcome.tcpStatus.name,
                  tcpMs = outcome.tcpMs,
                  tlsStatus = outcome.tlsStatus.name,
                  tlsMs = outcome.tlsMs,
                  wsStatus = outcome.wsStatus.name,
                  wsMs = outcome.wsMs,
                  wsHttpCode = outcome.wsHttpCode,
                  failureCause = outcome.failureCause.name,
                  detailMessageFa = outcome.detailMessageFa,
                  detailMessageEn = outcome.detailMessageEn,
                  phase1Qualified = outcome.phase1Qualified,
                  updatedAtMillis = System.currentTimeMillis(),
                )
              scanDao.upsertResult(updatedRow)

              if (
                outcome.failureCause == FailureCause.TCP_UNREACHABLE ||
                  outcome.failureCause == FailureCause.NO_INTERNET
              ) {
                netFailureStreak.incrementAndGet()
              } else if (outcome.phase1Qualified) {
                netFailureStreak.set(0)
              }

              val eventCode =
                when {
                  outcome.overallStatus == OverallIpStatus.SKIPPED ->
                    "SKIPPED_${outcome.failureCause.name}"
                  outcome.wsStatus == SubStepStatus.PASS -> "WS_UPGRADE_101"
                  outcome.tlsStatus == SubStepStatus.PASS -> "TLS_CONNECTED"
                  outcome.tcpStatus == SubStepStatus.PASS &&
                    outcome.overallStatus == OverallIpStatus.TCP_PASS -> "TCP_CONNECTED"
                  outcome.failureCause == FailureCause.TLS_CERT_ERROR ||
                    outcome.failureCause == FailureCause.TLS_HANDSHAKE_FAILED ||
                    outcome.failureCause == FailureCause.TLS_TIMEOUT -> "TLS_HANDSHAKE_FAILED"
                  outcome.failureCause == FailureCause.WS_UPGRADE_HTTP_ERROR ||
                    outcome.failureCause == FailureCause.WS_TIMEOUT ||
                    outcome.failureCause == FailureCause.WS_FAILED -> "WS_HANDSHAKE_FAILED"
                  else -> outcome.failureCause.name
                }

              val logLevel =
                when {
                  outcome.overallStatus == OverallIpStatus.SKIPPED -> LogLevel.SKIP
                  outcome.phase1Qualified -> LogLevel.PASS
                  else -> LogLevel.FAIL
                }
              val duration = outcome.wsMs ?: outcome.tlsMs ?: outcome.tcpMs
              emitLog(
                sessionId = sessionId,
                ip = outcome.ip,
                stage = "PHASE1",
                eventCode = eventCode,
                level = logLevel,
                durationMs = duration,
                msgFa = outcome.detailMessageFa,
                msgEn = outcome.detailMessageEn,
              )

              val done = processedAtomic.incrementAndGet()
              val elapsedSec = ((System.currentTimeMillis() - startTimeMs) / 1000f).coerceAtLeast(0.5f)
              val speed = done / elapsedSec
              refreshSessionCounts(
                sessionId = sessionId,
                phase = if (isStoppedFlag.get()) ScanPhase.STOPPED else ScanPhase.PHASE1_PROBING,
                speed = speed,
                ispWarning = netFailureStreak.get() >= 25,
              )
            }
          }
        }
        .awaitAll()
    }

    val finalPhase = if (isStoppedFlag.get()) ScanPhase.STOPPED else ScanPhase.PHASE1_COMPLETED
    refreshSessionCounts(sessionId, finalPhase, 0f, netFailureStreak.get() >= 25)
    emitLog(
      sessionId = sessionId,
      ip = "-",
      stage = "PHASE1",
      eventCode = if (finalPhase == ScanPhase.STOPPED) "PHASE1_STOPPED" else "PHASE1_COMPLETED",
      level = LogLevel.INFO,
      durationMs = System.currentTimeMillis() - startTimeMs,
      msgFa =
        if (finalPhase == ScanPhase.STOPPED) "مرحله اول توسط کاربر متوقف شد"
        else "مرحله اول با موفقیت به پایان رسید؛ آماده انتخاب IP برای تست تونل",
      msgEn =
        if (finalPhase == ScanPhase.STOPPED) "Phase 1 stopped by user"
        else "Phase 1 completed; ready for Phase 2 Xray tunnel selection",
    )
    onServiceFinished?.invoke()
  }

  fun startPhase2TunnelScan(
    sessionId: String,
    settings: ScanSettings,
    vlessConfig: VlessConfig,
    selectionMode: Phase2SelectionMode,
    customCount: Int,
    explicitSelectedIps: Set<String>? = null,
  ) {
    activeJob?.cancel()
    isPausedFlag.set(false)
    isStoppedFlag.set(false)

    activeJob =
      scope.launch {
        try {
          val allRows = scanDao.getResultsForSession(sessionId)
          val qualifiedSorted =
            allRows
              .filter { it.phase1Qualified }
              .sortedWith(
                compareByDescending<ScanResultEntity> { it.wsStatus == SubStepStatus.PASS.name }
                  .thenByDescending { it.tlsStatus == SubStepStatus.PASS.name }
                  .thenBy { it.wsMs ?: it.tlsMs ?: it.tcpMs ?: Long.MAX_VALUE }
              )

          val chosenRows =
            if (!explicitSelectedIps.isNullOrEmpty()) {
              qualifiedSorted.filter { it.ip in explicitSelectedIps }
            } else {
              when (selectionMode) {
                Phase2SelectionMode.ALL -> qualifiedSorted
                Phase2SelectionMode.TOP_50 -> qualifiedSorted.take(50)
                Phase2SelectionMode.TOP_100 -> qualifiedSorted.take(100)
                Phase2SelectionMode.CUSTOM -> qualifiedSorted.take(customCount.coerceAtLeast(1))
              }
            }

          if (chosenRows.isEmpty()) {
            _liveState.update {
              it.copy(lastErrorBanner = "هیچ IP واجد شرایطی از مرحله اول برای تست تونل وجود ندارد")
            }
            onServiceFinished?.invoke()
            return@launch
          }

          sessionMutex.withLock {
            scanDao.getSession(sessionId)?.let { s ->
              scanDao.upsertSession(
                s.copy(
                  phase = ScanPhase.PHASE2_TUNNEL.name,
                  phase2TargetCount = chosenRows.size,
                  phase2CompletedCount = 0,
                  vlessRedactedSummary = vlessConfig.redactedSummary(),
                  updatedAtMillis = System.currentTimeMillis(),
                )
              )
            }
          }

          _liveState.update {
            it.copy(
              activeSessionId = sessionId,
              phase = ScanPhase.PHASE2_TUNNEL,
              totalPhase2Targets = chosenRows.size,
              processedPhase2 = 0,
            )
          }
          notifyService()

          emitLog(
            sessionId = sessionId,
            ip = "-",
            stage = "PHASE2",
            eventCode = "PHASE2_STARTED",
            level = LogLevel.INFO,
            durationMs = null,
            msgFa =
              "شروع تست واقعی تونل Xray روی ${chosenRows.size} IP منتخب (${settings.tunnelRepetitions} تکرار)",
            msgEn =
              "Phase 2 Xray tunnel test started on ${chosenRows.size} selected IPs (${settings.tunnelRepetitions} runs)",
          )

          val startTime = System.currentTimeMillis()
          var processed = 0

          // Sequential execution guarantees clean ephemeral loopback ports & zero Go callback collisions
          for (row in chosenRows) {
            while (isPausedFlag.get() && !isStoppedFlag.get()) {
              delay(150L)
            }
            if (isStoppedFlag.get()) break

            val cand =
              IpCandidate(
                ip = row.ip,
                ipVersion = enumValueOf(row.ipVersion),
                sourceCidr = row.sourceCidr,
                isManual = row.isManual,
              )

            val outcome = xrayRunner.runTunnelTest(cand, vlessConfig, settings)
            val updatedRow =
              row.copy(
                overallStatus = outcome.tunnelStatus.name,
                tunnelStatus = outcome.tunnelStatus.name,
                tunnelAttempts = outcome.attempts,
                tunnelSuccesses = outcome.successes,
                tunnelTimeoutsOrDrops = outcome.timeoutsOrDrops,
                tunnelMedianMs = outcome.medianMs,
                tunnelMinMs = outcome.minMs,
                tunnelMaxMs = outcome.maxMs,
                externalIpObserved = outcome.externalIpObserved,
                downloadKbps = outcome.downloadKbps,
                stabilityScore = outcome.stabilityScore,
                failureCause = outcome.failureCause.name,
                detailMessageFa = outcome.detailMessageFa,
                detailMessageEn = outcome.detailMessageEn,
                updatedAtMillis = System.currentTimeMillis(),
              )
            scanDao.upsertResult(updatedRow)

            val eventCode =
              when {
                outcome.tunnelStatus == OverallIpStatus.TUNNEL_PASS -> "TUNNEL_PASS"
                outcome.tunnelStatus == OverallIpStatus.INCONCLUSIVE -> "TUNNEL_INCONCLUSIVE"
                else -> outcome.failureCause.name
              }
            val level =
              when (outcome.tunnelStatus) {
                OverallIpStatus.TUNNEL_PASS -> LogLevel.PASS
                OverallIpStatus.INCONCLUSIVE -> LogLevel.SKIP
                else -> LogLevel.FAIL
              }
            emitLog(
              sessionId = sessionId,
              ip = outcome.ip,
              stage = "PHASE2",
              eventCode = eventCode,
              level = level,
              durationMs = outcome.medianMs,
              msgFa = outcome.detailMessageFa,
              msgEn = outcome.detailMessageEn,
            )

            processed++
            val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000f).coerceAtLeast(0.5f)
            refreshSessionCounts(
              sessionId = sessionId,
              phase = ScanPhase.PHASE2_TUNNEL,
              speed = processed / elapsedSec,
              ispWarning = false,
              phase2DoneOverride = processed,
              phase2TargetOverride = chosenRows.size,
            )
          }

          val finalPhase = if (isStoppedFlag.get()) ScanPhase.STOPPED else ScanPhase.COMPLETED
          refreshSessionCounts(
            sessionId = sessionId,
            phase = finalPhase,
            speed = 0f,
            ispWarning = false,
            phase2DoneOverride = processed,
            phase2TargetOverride = chosenRows.size,
          )
          emitLog(
            sessionId = sessionId,
            ip = "-",
            stage = "PHASE2",
            eventCode = if (finalPhase == ScanPhase.STOPPED) "PHASE2_STOPPED" else "PHASE2_COMPLETED",
            level = LogLevel.INFO,
            durationMs = System.currentTimeMillis() - startTime,
            msgFa =
              if (finalPhase == ScanPhase.STOPPED) "تست تونل متوقف شد"
              else "تست واقعی تونل Xray کامل شد",
            msgEn =
              if (finalPhase == ScanPhase.STOPPED) "Phase 2 tunnel test stopped"
              else "Phase 2 Xray tunnel verification completed",
          )
        } catch (ce: CancellationException) {
          throw ce
        } catch (e: Exception) {
          _liveState.update { it.copy(lastErrorBanner = e.message) }
        } finally {
          onServiceFinished?.invoke()
        }
      }
  }

  fun pauseScan() {
    isPausedFlag.set(true)
    _liveState.update { it.copy(phase = ScanPhase.PAUSED) }
    scope.launch {
      _liveState.value.activeSessionId?.let { id ->
        refreshSessionCounts(id, ScanPhase.PAUSED, 0f, false)
      }
    }
    notifyService()
  }

  fun resumeScan(settings: ScanSettings, vlessConfig: VlessConfig?) {
    isPausedFlag.set(false)
    val currentId = _liveState.value.activeSessionId ?: return
    if (activeJob?.isActive == true) {
      val resumedPhase =
        if (_liveState.value.totalPhase2Targets > 0) ScanPhase.PHASE2_TUNNEL
        else ScanPhase.PHASE1_PROBING
      _liveState.update { it.copy(phase = resumedPhase) }
      notifyService()
      return
    }
    // Resume from persisted Room state after app restart!
    isStoppedFlag.set(false)
    activeJob =
      scope.launch {
        _liveState.update { it.copy(phase = ScanPhase.PHASE1_PROBING) }
        notifyService()
        runPhase1Loop(currentId, settings, vlessConfig)
      }
  }

  fun stopScan() {
    isStoppedFlag.set(true)
    isPausedFlag.set(false)
    activeJob?.cancel()
    val currentId = _liveState.value.activeSessionId
    _liveState.update { it.copy(phase = ScanPhase.STOPPED, currentSpeedIpsPerSec = 0f) }
    scope.launch {
      if (currentId != null) {
        refreshSessionCounts(currentId, ScanPhase.STOPPED, 0f, false)
      }
    }
    onServiceFinished?.invoke()
  }

  fun clearCurrentView() {
    stopScan()
    scope.launch { settingsStore.saveActiveSessionId(null) }
    _liveState.value = LiveScanUiState()
  }

  fun selectHistorySession(sessionId: String) {
    if (activeJob?.isActive == true) return
    scope.launch {
      settingsStore.saveActiveSessionId(sessionId)
      val session = scanDao.getSession(sessionId) ?: return@launch
      val results = scanDao.getResultsForSession(sessionId)
      _liveState.value =
        LiveScanUiState(
          activeSessionId = session.sessionId,
          phase = runCatching { ScanPhase.valueOf(session.phase) }.getOrDefault(ScanPhase.COMPLETED),
          totalPhase1Targets = session.validInputs,
          processedPhase1 = session.phase1CompletedCount,
          totalPhase2Targets = session.phase2TargetCount,
          processedPhase2 = session.phase2CompletedCount,
          tcpPassCount = session.tcpPassCount,
          tlsPassCount = session.tlsPassCount,
          wsPassCount = session.wsPassCount,
          failCount = session.tcpFailCount + session.tlsFailCount + session.wsFailCount,
          skippedCount = session.skippedCount,
          tunnelPassCount = session.tunnelPassCount,
          tunnelFailCount = session.tunnelFailCount,
          inconclusiveCount = session.inconclusiveCount,
          ratioNote = session.ratioNote,
          qualifiedPhase1Count = results.count { it.phase1Qualified },
        )
    }
  }

  private suspend fun refreshSessionCounts(
    sessionId: String,
    phase: ScanPhase,
    speed: Float,
    ispWarning: Boolean,
    phase2DoneOverride: Int? = null,
    phase2TargetOverride: Int? = null,
  ) {
    sessionMutex.withLock {
      val rows = scanDao.getResultsForSession(sessionId)
      val session = scanDao.getSession(sessionId) ?: return@withLock

      val phase1Done = rows.count { it.overallStatus != OverallIpStatus.PENDING.name }
      val tcpPass = rows.count { it.tcpStatus == SubStepStatus.PASS.name }
      val tcpFail = rows.count { it.tcpStatus == SubStepStatus.FAIL.name }
      val tlsPass =
        rows.count {
          it.tlsStatus == SubStepStatus.PASS.name ||
            it.tlsStatus == SubStepStatus.DIAGNOSTIC_INSECURE_PASS.name
        }
      val tlsFail = rows.count { it.tlsStatus == SubStepStatus.FAIL.name }
      val wsPass = rows.count { it.wsStatus == SubStepStatus.PASS.name }
      val wsFail = rows.count { it.wsStatus == SubStepStatus.FAIL.name }
      val skipped = rows.count { it.overallStatus == OverallIpStatus.SKIPPED.name }
      val qualified = rows.count { it.phase1Qualified }
      val tunnelPass = rows.count { it.tunnelStatus == OverallIpStatus.TUNNEL_PASS.name }
      val tunnelFail = rows.count { it.tunnelStatus == OverallIpStatus.TUNNEL_FAIL.name }
      val inconclusive = rows.count { it.tunnelStatus == OverallIpStatus.INCONCLUSIVE.name }

      val p2Done = phase2DoneOverride ?: session.phase2CompletedCount
      val p2Target = phase2TargetOverride ?: session.phase2TargetCount

      val updated =
        session.copy(
          phase = phase.name,
          phase1CompletedCount = phase1Done,
          tcpPassCount = tcpPass,
          tcpFailCount = tcpFail,
          tlsPassCount = tlsPass,
          tlsFailCount = tlsFail,
          wsPassCount = wsPass,
          wsFailCount = wsFail,
          skippedCount = skipped,
          phase2TargetCount = p2Target,
          phase2CompletedCount = p2Done,
          tunnelPassCount = tunnelPass,
          tunnelFailCount = tunnelFail,
          inconclusiveCount = inconclusive,
          updatedAtMillis = System.currentTimeMillis(),
        )
      scanDao.upsertSession(updated)

      _liveState.update {
        it.copy(
          activeSessionId = sessionId,
          phase = phase,
          totalPhase1Targets = session.validInputs,
          processedPhase1 = phase1Done,
          totalPhase2Targets = p2Target,
          processedPhase2 = p2Done,
          tcpPassCount = tcpPass,
          tlsPassCount = tlsPass,
          wsPassCount = wsPass,
          failCount = tcpFail + tlsFail + wsFail,
          skippedCount = skipped,
          tunnelPassCount = tunnelPass,
          tunnelFailCount = tunnelFail,
          inconclusiveCount = inconclusive,
          currentSpeedIpsPerSec = speed,
          ratioNote = session.ratioNote,
          possibleIspDisruptionWarning = ispWarning,
          qualifiedPhase1Count = qualified,
        )
      }
      notifyService()
    }
  }

  private fun notifyService() {
    onServiceNotificationUpdate?.invoke(_liveState.value)
  }

  private suspend fun emitLog(
    sessionId: String,
    ip: String,
    stage: String,
    eventCode: String,
    level: LogLevel,
    durationMs: Long?,
    msgFa: String,
    msgEn: String,
  ) {
    scanDao.insertLog(
      ScanLogEntity(
        sessionId = sessionId,
        timestampMillis = System.currentTimeMillis(),
        ip = ip,
        stage = stage,
        eventCode = eventCode,
        level = level.name,
        durationMs = durationMs,
        messageFa = ReportExporter.redactSensitiveText(msgFa),
        messageEn = ReportExporter.redactSensitiveText(msgEn),
      )
    )
  }
}
