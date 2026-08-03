package dev.linjian.healthbridge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class HealthSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!BridgePrefs.autoSync(applicationContext)) return Result.success()
        return try {
            HealthUploader.collectAndUpload(applicationContext)
            Result.success()
        } catch (e: SecurityException) {
            BridgePrefs.recordError(applicationContext, e.message ?: "健康权限不足")
            Result.failure()
        } catch (e: IllegalArgumentException) {
            BridgePrefs.recordError(applicationContext, e.message ?: "连接设置不完整")
            Result.failure()
        } catch (e: Exception) {
            BridgePrefs.recordError(applicationContext, e.message ?: e.javaClass.simpleName)
            Result.retry()
        }
    }
}
