package com.looker.installer.installers

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.looker.core.common.SdkCheck
import com.looker.core.common.cache.Cache
import com.looker.core.common.sdkAbove
import com.looker.core.model.newer.PackageName
import com.looker.installer.model.InstallItem
import com.looker.installer.model.InstallItemState
import com.looker.installer.model.InstallState
import com.looker.installer.model.statesTo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class SessionInstaller(private val context: Context) : BaseInstaller {

	private val sessionInstaller = context.packageManager.packageInstaller
	private val intent = Intent(context, SessionInstallerService::class.java)
	private var installerCallbacks = mutableListOf<PackageInstaller.SessionCallback>()

	companion object {
		private val flags = if (SdkCheck.isSnowCake) PendingIntent.FLAG_MUTABLE else 0
	}

	override suspend fun performInstall(
		installItem: InstallItem,
		state: MutableStateFlow<InstallItemState>
	) {
		state.emit(installItem statesTo InstallState.Installing)
		val cacheFile = Cache.getReleaseFile(context, installItem.installFileName)
		val sessionParams =
			PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
		sdkAbove(sdk = Build.VERSION_CODES.S) {
			sessionParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
		}
		val id = sessionInstaller.createSession(sessionParams)
		val installerCallback = object : PackageInstaller.SessionCallback() {
			override fun onCreated(sessionId: Int) {}
			override fun onBadgingChanged(sessionId: Int) {}
			override fun onActiveChanged(sessionId: Int, active: Boolean) {}
			override fun onProgressChanged(sessionId: Int, progress: Float) {}

			override fun onFinished(sessionId: Int, success: Boolean) {
				if (sessionId == id) state.tryEmit(installItem statesTo InstallState.Installed)
			}
		}
		installerCallbacks.add(installerCallback)

		sessionInstaller.registerSessionCallback(
			installerCallbacks.last(),
			Handler(Looper.getMainLooper())
		)

		val session = sessionInstaller.openSession(id)

		session.use { activeSession ->
			val sizeBytes = cacheFile.length()
			cacheFile.inputStream().use { fileStream ->
				activeSession.openWrite(cacheFile.name, 0, sizeBytes).use { outputStream ->
					fileStream.copyTo(outputStream)
					activeSession.fsync(outputStream)
				}
			}

			val pendingIntent = PendingIntent.getService(context, id, intent, flags)

			activeSession.commit(pendingIntent.intentSender)
		}
	}

	@SuppressLint("MissingPermission")
	override suspend fun performUninstall(packageName: PackageName) =
		suspendCancellableCoroutine {
			intent.putExtra(
				SessionInstallerService.KEY_ACTION,
				SessionInstallerService.ACTION_UNINSTALL
			)
			val pendingIntent = PendingIntent.getService(context, -1, intent, flags)

			sessionInstaller.uninstall(packageName.name, pendingIntent.intentSender)
			it.resume(Unit)
		}

	override fun cleanup() {
		installerCallbacks.forEach { sessionInstaller.unregisterSessionCallback(it) }
		sessionInstaller.allSessions.forEach { sessionInstaller.abandonSession(it.sessionId) }
	}
}