package com.example

import android.app.Application
import com.example.core.network.AndroidLibXrayRunner
import com.example.core.network.AndroidNetworkCapabilityChecker
import com.example.core.network.AwsIpRangeRepository
import com.example.core.network.Phase1ProbeEngine
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsDataStore
import com.example.scanner.ScanCoordinator

class CloudFrontScannerApp : Application() {

  lateinit var database: AppDatabase
    private set

  lateinit var settingsStore: SettingsDataStore
    private set

  lateinit var awsRepository: AwsIpRangeRepository
    private set

  lateinit var xrayRunner: AndroidLibXrayRunner
    private set

  lateinit var scanCoordinator: ScanCoordinator
    private set

  override fun onCreate() {
    super.onCreate()
    database = AppDatabase.getInstance(this)
    settingsStore = SettingsDataStore(this)
    awsRepository = AwsIpRangeRepository(database.scanDao())
    val networkChecker = AndroidNetworkCapabilityChecker(this)
    val phase1Engine = Phase1ProbeEngine(networkChecker)
    xrayRunner = AndroidLibXrayRunner(this)
    scanCoordinator =
      ScanCoordinator(
        scanDao = database.scanDao(),
        settingsStore = settingsStore,
        awsRepository = awsRepository,
        phase1Engine = phase1Engine,
        xrayRunner = xrayRunner,
      )
  }
}
