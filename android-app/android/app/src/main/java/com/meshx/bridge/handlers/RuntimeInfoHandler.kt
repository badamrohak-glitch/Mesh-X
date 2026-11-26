package com.meshx.bridge.handlers

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.meshx.bridge.BridgeCommandHandler
import com.meshx.bridge.BridgeContract
import com.meshx.bridge.BridgeRequest
import org.json.JSONArray
import org.json.JSONObject

data class NativeRuntimeInfo(
  val protocolVersion: Int,
  val platform: String,
  val manufacturer: String,
  val model: String,
  val androidRelease: String,
  val sdkInt: Int,
  val appId: String,
  val appVersionName: String,
  val appVersionCode: Long,
  val isDebugBuild: Boolean,
)

interface RuntimeInfoProvider {
  fun readRuntimeInfo(): NativeRuntimeInfo
}

class AndroidRuntimeInfoProvider(
  private val context: Context,
) : RuntimeInfoProvider {
  override fun readRuntimeInfo(): NativeRuntimeInfo {
    val pm = context.packageManager
    val packageName = context.packageName

    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
      @Suppress("DEPRECATION")
      pm.getPackageInfo(packageName, 0)
    }

    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageInfo.longVersionCode
    } else {
      @Suppress("DEPRECATION")
      packageInfo.versionCode.toLong()
    }

    val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    return NativeRuntimeInfo(
      protocolVersion = BridgeContract.PROTOCOL_VERSION,
      platform = "android",
      manufacturer = Build.MANUFACTURER ?: "unknown",
      model = Build.MODEL ?: "unknown",
      androidRelease = Build.VERSION.RELEASE ?: "unknown",
      sdkInt = Build.VERSION.SDK_INT,
      appId = packageName,
      appVersionName = packageInfo.versionName ?: "0.0.0",
      appVersionCode = versionCode,
      isDebugBuild = isDebug,
    )
  }
}

class RuntimeInfoHandler(
  private val provider: RuntimeInfoProvider,
  private val availableCommandsProvider: () -> List<String>,
) : BridgeCommandHandler {
  override val commandType: String = "GET_RUNTIME_INFO"

  override suspend fun handle(request: BridgeRequest): JSONObject {
    val info = provider.readRuntimeInfo()
    return JSONObject().apply {
      put("protocolVersion", info.protocolVersion)
      put("platform", info.platform)
      put("manufacturer", info.manufacturer)
      put("model", info.model)
      put("androidRelease", info.androidRelease)
      put("sdkInt", info.sdkInt)
      put("appId", info.appId)
      put("appVersionName", info.appVersionName)
      put("appVersionCode", info.appVersionCode)
      put("isDebugBuild", info.isDebugBuild)
      put("availableCommands", JSONArray(availableCommandsProvider()))
    }
  }
}
