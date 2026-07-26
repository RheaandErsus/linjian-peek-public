package dev.linjian.healthbridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object HealthUploader {
    suspend fun collectAndUpload(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val server = BridgePrefs.server(context)
        val token = BridgePrefs.token(context)
        val device = BridgePrefs.device(context)
        require(server.startsWith("https://")) { "服务器地址必须以 https:// 开头" }
        require(token.isNotBlank()) { "Token 不能为空" }
        require(HealthPermissions.hasForeground(context)) { "尚未授予健康数据读取权限" }

        val health = HealthDataReader.collect(context)
        val payload = JSONObject()
            .put("device_id", device)
            .put("health", health)

        val conn = (URL("$server/api/health/state").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Auth-Token", token)
            val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("服务器返回 HTTP $code：${body.take(160)}")
            val response = if (body.isBlank()) JSONObject().put("ok", true) else JSONObject(body)
            if (!response.optBoolean("ok", false)) error(response.optString("error", "服务器未确认上传"))
            BridgePrefs.recordSuccess(context, health.getString("generated_at"))
            response.put("health", health)
        } finally {
            conn.disconnect()
        }
    }
}
