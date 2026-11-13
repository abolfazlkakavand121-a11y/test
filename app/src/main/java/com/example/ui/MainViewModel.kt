package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.CloudFrontScannerApp
import com.example.core.network.CidrMath
import com.example.core.network.VlessParser
import com.example.data.local.ExportFormat
import com.example.data.local.ReportExporter
import com.example.data.local.ScanLogEntity
import com.example.data.local.ScanResultEntity
import com.example.data.local.ScanSessionEntity
import com.example.domain.model.AppLanguage
import com.example.domain.model.AwsIpRangesSnapshot
import com.example.domain.model.IpFamilyPreference
import com.example.domain.model.ManualParseReport
import com.example.domain.model.OverallIpStatus
import com.example.domain.model.Phase2SelectionMode
import com.example.domain.model.ScanMode
import com.example.domain.model.ScanPreset
import com.example.domain.model.ScanSettings
import com.example.domain.model.SubStepStatus
import com.example.domain.model.ThemeMode
import com.example.domain.model.TunnelTestUrl
import com.example.domain.model.VlessCompatibilityReport
import com.example.domain.model.VlessConfig
import com.example.scanner.LiveScanUiState
import com.example.scanner.ScanForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ResultStatusFilter {
  ALL,
  TUNNEL_PASS,
  TUNNEL_FAIL,
  PHASE1_PASS_ONLY,
  WS_PASS,
  TLS_PASS,
  TCP_PASS,
  INCONCLUSIVE,
  SKIPPED,
}

enum class ResultSortBy {
  RANK_DEFAULT,
  TUNNEL_LATENCY,
  STABILITY,
  WS_LATENCY,
  TLS_LATENCY,
  TCP_LATENCY,
}

enum class LogFilterType {
  ALL,
  PASS,
  FAIL,
  SKIP,
}

data class AwsRangesUiState(
  val isLoading: Boolean = false,
  val snapshot: AwsIpRangesSnapshot? = null,
  val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val app = application as CloudFrontScannerApp
  private val scanDao = app.database.scanDao()
  private val settingsStore = app.settingsStore
  private val awsRepo = app.awsRepository
  private val coordinator = app.scanCoordinator

  val settings: StateFlow<ScanSettings> =
    settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, ScanSettings())

  val rawVlessUri: StateFlow<String> =
    settingsStore.vlessUriFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")

  val manualInputText: StateFlow<String> =
    settingsStore.manualInputFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")

  val parsedVless: StateFlow<Result<VlessConfig>?> =
    rawVlessUri
      .map { uri -> if (uri.isBlank()) null else VlessParser.parse(uri) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  val vlessCompatibility: StateFlow<VlessCompatibilityReport?> =
    parsedVless
      .map { res -> res?.getOrNull()?.let { VlessParser.analyzeCloudFrontCompatibility(it) } }
      .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  val manualParseReport: StateFlow<ManualParseReport?> =
    combine(manualInputText, settings) { text, cfg ->
        if (text.isBlank()) null
        else CidrMath.parseManualInput(text, cfg.manualCidrSampleCap, cfg.manualCidrScanAllUpTo)
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  private val _awsUiState = MutableStateFlow(AwsRangesUiState())
  val awsUiState: StateFlow<AwsRangesUiState> = _awsUiState.asStateFlow()

  val liveScanState: StateFlow<LiveScanUiState> = coordinator.liveState

  val activeResults: StateFlow<List<ScanResultEntity>> =
    liveScanState
      .map { it.activeSessionId }
      .flatMapLatest { sid ->
        if (sid.isNullOrBlank()) flowOf(emptyList()) else scanDao.observeResultsForSession(sid)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val activeLogs: StateFlow<List<ScanLogEntity>> =
    liveScanState
      .map { it.activeSessionId }
      .flatMapLatest { sid ->
        if (sid.isNullOrBlank()) flowOf(emptyList()) else scanDao.observeLogsForSession(sid)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val historySessions: StateFlow<List<ScanSessionEntity>> =
    scanDao
      .observeAllSessions()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _resultSearchQuery = MutableStateFlow("")
  val resultSearchQuery: StateFlow<String> = _resultSearchQuery.asStateFlow()

  private val _resultStatusFilter = MutableStateFlow(ResultStatusFilter.ALL)
  val resultStatusFilter: StateFlow<ResultStatusFilter> = _resultStatusFilter.asStateFlow()

  private val _resultIpFamilyFilter = MutableStateFlow<String?>(null)
  val resultIpFamilyFilter: StateFlow<String?> = _resultIpFamilyFilter.asStateFlow()

  private val _resultSortBy = MutableStateFlow(ResultSortBy.RANK_DEFAULT)
  val resultSortBy: StateFlow<ResultSortBy> = _resultSortBy.asStateFlow()

  private val _resultsDisplayLimit = MutableStateFlow(100)
  val resultsDisplayLimit: StateFlow<Int> = _resultsDisplayLimit.asStateFlow()

  private val _logFilter = MutableStateFlow(LogFilterType.ALL)
  val logFilter: StateFlow<LogFilterType> = _logFilter.asStateFlow()

  private val _selectedPhase2Ips = MutableStateFlow<Set<String>>(emptySet())
  val selectedPhase2Ips: StateFlow<Set<String>> = _selectedPhase2Ips.asStateFlow()

  private val _userBannerMsg = MutableStateFlow<String?>(null)
  val userBannerMsg: StateFlow<String?> = _userBannerMsg.asStateFlow()

  val filteredSortedResults: StateFlow<List<ScanResultEntity>> =
    combine(
      activeResults,
      _resultSearchQuery,
      _resultStatusFilter,
      _resultIpFamilyFilter,
      _resultSortBy,
    ) { list, query, filter, fam, sortBy ->
      val q = query.trim().lowercase()
      val filtered =
        list.filter { r ->
          val matchesQuery =
            q.isEmpty() ||
              r.ip.lowercase().contains(q) ||
              r.overallStatus.lowercase().contains(q) ||
              r.detailMessageFa.lowercase().contains(q) ||
              r.detailMessageEn.lowercase().contains(q) ||
              r.failureCause.lowercase().contains(q)
          val matchesFamily = fam == null || r.ipVersion == fam
          val matchesStatus =
            when (filter) {
              ResultStatusFilter.ALL -> true
              ResultStatusFilter.TUNNEL_PASS -> r.tunnelStatus == OverallIpStatus.TUNNEL_PASS.name
              ResultStatusFilter.TUNNEL_FAIL -> r.tunnelStatus == OverallIpStatus.TUNNEL_FAIL.name
              ResultStatusFilter.PHASE1_PASS_ONLY ->
                r.phase1Qualified && r.tunnelStatus != OverallIpStatus.TUNNEL_PASS.name
              ResultStatusFilter.WS_PASS -> r.wsStatus == SubStepStatus.PASS.name
              ResultStatusFilter.TLS_PASS ->
                r.tlsStatus == SubStepStatus.PASS.name ||
                  r.tlsStatus == SubStepStatus.DIAGNOSTIC_INSECURE_PASS.name
              ResultStatusFilter.TCP_PASS -> r.tcpStatus == SubStepStatus.PASS.name
              ResultStatusFilter.INCONCLUSIVE -> r.overallStatus == OverallIpStatus.INCONCLUSIVE.name
              ResultStatusFilter.SKIPPED -> r.overallStatus == OverallIpStatus.SKIPPED.name
            }
          matchesQuery && matchesFamily && matchesStatus
        }

      when (sortBy) {
        ResultSortBy.RANK_DEFAULT ->
          filtered.sortedWith(
            compareByDescending<ScanResultEntity> { it.tunnelStatus == OverallIpStatus.TUNNEL_PASS.name }
              .thenByDescending { it.stabilityScore }
              .thenBy { it.tunnelMedianMs ?: Long.MAX_VALUE }
              .thenByDescending { it.phase1Qualified }
              .thenByDescending { it.wsStatus == SubStepStatus.PASS.name }
              .thenByDescending { it.tlsStatus == SubStepStatus.PASS.name }
              .thenBy { it.wsMs ?: it.tlsMs ?: it.tcpMs ?: Long.MAX_VALUE }
          )
        ResultSortBy.TUNNEL_LATENCY ->
          filtered.sortedBy { it.tunnelMedianMs ?: Long.MAX_VALUE }
        ResultSortBy.STABILITY ->
          filtered.sortedByDescending { it.stabilityScore }
        ResultSortBy.WS_LATENCY ->
          filtered.sortedBy { it.wsMs ?: Long.MAX_VALUE }
        ResultSortBy.TLS_LATENCY ->
          filtered.sortedBy { it.tlsMs ?: Long.MAX_VALUE }
        ResultSortBy.TCP_LATENCY ->
          filtered.sortedBy { it.tcpMs ?: Long.MAX_VALUE }
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val filteredLogs: StateFlow<List<ScanLogEntity>> =
    combine(activeLogs, _logFilter) { logs, filter ->
      when (filter) {
        LogFilterType.ALL -> logs
        LogFilterType.PASS -> logs.filter { it.level == "PASS" }
        LogFilterType.FAIL -> logs.filter { it.level == "FAIL" }
        LogFilterType.SKIP -> logs.filter { it.level == "SKIP" }
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val xrayVersionText: String by lazy { app.xrayRunner.xrayVersion() }

  init {
    viewModelScope.launch {
      coordinator.restoreLastSessionIfIdle()
      val cached = awsRepo.loadCachedSnapshot()
      if (cached != null) {
        _awsUiState.value = AwsRangesUiState(snapshot = cached)
      } else {
        refreshAwsRanges(force = true)
      }
    }
  }

  fun clearBannerMsg() {
    _userBannerMsg.value = null
  }

  fun refreshAwsRanges(force: Boolean = true) {
    viewModelScope.launch {
      _awsUiState.value = _awsUiState.value.copy(isLoading = true, errorMessage = null)
      val res = awsRepo.fetchLatestOrCached(forceRefresh = force)
      res.fold(
        onSuccess = { snap ->
          _awsUiState.value = AwsRangesUiState(isLoading = false, snapshot = snap, errorMessage = null)
        },
        onFailure = { err ->
          _awsUiState.value =
            _awsUiState.value.copy(isLoading = false, errorMessage = err.message ?: "Fetch failed")
        },
      )
    }
  }

  fun updateVlessUri(uri: String) {
    viewModelScope.launch { settingsStore.saveVlessUri(uri) }
  }

  fun updateManualInput(text: String) {
    viewModelScope.launch { settingsStore.saveManualInput(text) }
  }

  fun importTextFromSafUri(uri: Uri, targetIsVless: Boolean) {
    viewModelScope.launch {
      val content =
        withContext(Dispatchers.IO) {
          runCatching {
            app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
          }
            .getOrElse { "" }
        }
      if (content.isBlank()) {
        _userBannerMsg.value = "فایل انتخاب‌شده خالی یا غیرقابل خواندن است"
        return@launch
      }
      if (targetIsVless) {
        val vlessLine =
          content.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("vless://", true) }
            ?: content.trim()
        settingsStore.saveVlessUri(vlessLine)
      } else {
        settingsStore.saveManualInput(content)
      }
    }
  }

  fun setScanMode(mode: ScanMode) {
    viewModelScope.launch { settingsStore.updateSettings { it.copy(scanMode = mode) } }
  }

  fun setIpFamilyPreference(pref: IpFamilyPreference) {
    viewModelScope.launch { settingsStore.updateSettings { it.copy(ipFamilyPreference = pref) } }
  }

  fun setAutoSampleCount(count: Int) {
    viewModelScope.launch {
      settingsStore.updateSettings { it.copy(autoSampleCount = count.coerceIn(1, 5000)) }
    }
  }

  fun setManualCidrSampleCap(count: Int) {
    viewModelScope.launch {
      settingsStore.updateSettings { it.copy(manualCidrSampleCap = count.coerceIn(1, 2000)) }
    }
  }

  fun applyPreset(preset: ScanPreset) {
    viewModelScope.launch { settingsStore.applyPreset(preset) }
  }

  fun setLanguage(lang: AppLanguage) {
    viewModelScope.launch { settingsStore.setLanguage(lang) }
  }

  fun setThemeMode(mode: ThemeMode) {
    viewModelScope.launch { settingsStore.setThemeMode(mode) }
  }

  fun updateSettings(transform: (ScanSettings) -> ScanSettings) {
    viewModelScope.launch { settingsStore.updateSettings(transform) }
  }

  fun addTunnelTestUrl(url: TunnelTestUrl) {
    viewModelScope.launch {
      settingsStore.updateSettings { cur -> cur.copy(tunnelTestUrls = cur.tunnelTestUrls + url) }
    }
  }

  fun toggleTunnelTestUrl(id: String) {
    viewModelScope.launch {
      settingsStore.updateSettings { cur ->
        cur.copy(
          tunnelTestUrls =
            cur.tunnelTestUrls.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        )
      }
    }
  }

  fun deleteTunnelTestUrl(id: String) {
    viewModelScope.launch {
      settingsStore.updateSettings { cur ->
        cur.copy(tunnelTestUrls = cur.tunnelTestUrls.filterNot { it.id == id })
      }
    }
  }

  fun startPhase1Scan() {
    val cfg = settings.value
    val vless = parsedVless.value?.getOrNull()
    val compat = vlessCompatibility.value
    if (vless != null && compat != null && !compat.isValid) {
      _userBannerMsg.value = compat.errors.firstOrNull() ?: "پیکربندی VLESS دارای خطاست"
      return
    }
    if (cfg.scanMode == ScanMode.AUTO_CLOUDFRONT && _awsUiState.value.snapshot == null) {
      _userBannerMsg.value = "ابتدا فهرست رسمی AWS را دریافت کنید"
      return
    }
    if (cfg.scanMode == ScanMode.MANUAL_INPUT && (manualParseReport.value?.uniqueIps?.isEmpty() != false)) {
      _userBannerMsg.value = "لطفاً حداقل یک IP یا CIDR معتبر وارد کنید"
      return
    }

    _selectedPhase2Ips.value = emptySet()
    _resultsDisplayLimit.value = 100
    ScanForegroundService.startService(app)
    coordinator.startPhase1Scan(
      settings = cfg,
      vlessConfig = vless,
      awsSnapshot = _awsUiState.value.snapshot,
      manualReport = manualParseReport.value,
    )
  }

  fun startPhase2TunnelScan(mode: Phase2SelectionMode, customCount: Int) {
    val sessionId = liveScanState.value.activeSessionId ?: return
    val vless = parsedVless.value?.getOrNull()
    if (vless == null) {
      _userBannerMsg.value = "برای تست واقعی تونل، ابتدا یک لینک vless:// معتبر وارد کنید"
      return
    }
    val compat = vlessCompatibility.value
    if (compat != null && !compat.canRunPhase2) {
      _userBannerMsg.value =
        compat.errors.firstOrNull() ?: "این کانفیگ برای تست تونل CloudFront قابل پشتیبانی نیست"
      return
    }
    _resultsDisplayLimit.value = 100
    ScanForegroundService.startService(app)
    coordinator.startPhase2TunnelScan(
      sessionId = sessionId,
      settings = settings.value,
      vlessConfig = vless,
      selectionMode = mode,
      customCount = customCount,
      explicitSelectedIps = _selectedPhase2Ips.value.takeIf { it.isNotEmpty() },
    )
  }

  fun togglePhase2IpSelection(ip: String) {
    val cur = _selectedPhase2Ips.value.toMutableSet()
    if (!cur.add(ip)) cur.remove(ip)
    _selectedPhase2Ips.value = cur
  }

  fun clearPhase2IpSelection() {
    _selectedPhase2Ips.value = emptySet()
  }

  fun pauseScan() = coordinator.pauseScan()

  fun resumeScan() {
    ScanForegroundService.startService(app)
    coordinator.resumeScan(settings.value, parsedVless.value?.getOrNull())
  }

  fun stopScan() = coordinator.stopScan()

  fun clearResultsView() {
    _selectedPhase2Ips.value = emptySet()
    coordinator.clearCurrentView()
  }

  fun loadHistorySession(sessionId: String) {
    _resultsDisplayLimit.value = 100
    coordinator.selectHistorySession(sessionId)
  }

  fun deleteHistorySession(sessionId: String) {
    viewModelScope.launch {
      if (liveScanState.value.activeSessionId == sessionId) {
        coordinator.clearCurrentView()
      }
      scanDao.deleteLogsForSession(sessionId)
      scanDao.deleteResultsForSession(sessionId)
      scanDao.deleteSession(sessionId)
    }
  }

  fun setResultSearchQuery(q: String) {
    _resultSearchQuery.value = q
  }

  fun setResultStatusFilter(filter: ResultStatusFilter) {
    _resultStatusFilter.value = filter
  }

  fun setResultIpFamilyFilter(fam: String?) {
    _resultIpFamilyFilter.value = fam
  }

  fun setResultSortBy(sortBy: ResultSortBy) {
    _resultSortBy.value = sortBy
  }

  fun showNext100Results() {
    _resultsDisplayLimit.value += 100
  }

  fun showAllResults() {
    _resultsDisplayLimit.value = Int.MAX_VALUE
  }

  fun setCustomDisplayLimit(limit: Int) {
    _resultsDisplayLimit.value = limit.coerceAtLeast(1)
  }

  fun setLogFilter(filter: LogFilterType) {
    _logFilter.value = filter
  }

  fun exportToSaf(uri: Uri, format: ExportFormat) {
    viewModelScope.launch {
      val sid = liveScanState.value.activeSessionId ?: return@launch
      val session = scanDao.getSession(sid) ?: return@launch
      val results = filteredSortedResults.value.ifEmpty { scanDao.getResultsForSession(sid) }
      val content = ReportExporter.buildContent(format, session, results)
      val res = ReportExporter.writeToSafUri(app, uri, content)
      _userBannerMsg.value =
        if (res.isSuccess) "گزارش ${format.extension.uppercase()} ذخیره شد"
        else "خطا در ذخیره فایل: ${res.exceptionOrNull()?.message}"
    }
  }

  fun createShareIntent(format: ExportFormat, onReady: (Intent) -> Unit) {
    viewModelScope.launch {
      val sid = liveScanState.value.activeSessionId ?: return@launch
      val session = scanDao.getSession(sid) ?: return@launch
      val results = filteredSortedResults.value.ifEmpty { scanDao.getResultsForSession(sid) }
      val content = ReportExporter.buildContent(format, session, results)
      val fileName = "cloudfront_scan_${sid}.${format.extension}"
      val file = ReportExporter.writeTempShareFile(app, fileName, content)
      val uri =
        FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
      val shareIntent =
        Intent(Intent.ACTION_SEND).apply {
          type = format.mimeType
          putExtra(Intent.EXTRA_STREAM, uri)
          putExtra(Intent.EXTRA_SUBJECT, "CloudFront Scanner Report ($sid)")
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
      onReady(Intent.createChooser(shareIntent, fileName))
    }
  }
}
