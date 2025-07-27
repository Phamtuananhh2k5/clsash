package com.github.kr328.clash.service.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class TokenApiService {
    companion object {
        private const val API_URL = "https://link.xn--iiq451n.com/api/index.php?token={token}"
        private const val TIMEOUT_MS = 30000L
        private val URL_PATTERN = Pattern.compile("https?://[^\\s\\r\\n\"'<>]+")
        private val TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9]{8,}$")
    }

    /**
     * Converts subscription token to profile URL
     * @param token The subscription token (alphanumeric, minimum 8 characters)
     * @return Result containing the profile URL or error
     */
    suspend fun getProfileUrl(token: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // Validate token format
                if (token.isBlank() || token.length < 8 || !TOKEN_PATTERN.matcher(token).matches()) {
                    return@withContext Result.failure(Exception("Token không hợp lệ. Token phải có ít nhất 8 ký tự và chỉ chứa chữ cái, số."))
                }

                withTimeout(TIMEOUT_MS) {
                    val url = API_URL.replace("{token}", token)
                    val connection = URL(url).openConnection() as HttpURLConnection

                    try {
                        connection.apply {
                            requestMethod = "GET"
                            connectTimeout = 25000
                            readTimeout = 25000
                            setRequestProperty("User-Agent", "ClashMetaForAndroid")
                            setRequestProperty("Accept", "application/json, text/plain, */*")
                        }

                        val responseCode = connection.responseCode
                        val responseText = if (responseCode == HttpURLConnection.HTTP_OK) {
                            connection.inputStream.bufferedReader().use { it.readText() }
                        } else {
                            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        }

                        when (responseCode) {
                            HttpURLConnection.HTTP_OK -> {
                                val profileUrl = extractProfileUrl(responseText)
                                if (profileUrl != null) {
                                    Result.success(profileUrl)
                                } else {
                                    Result.failure(Exception("Không tìm thấy URL profile trong phản hồi từ server"))
                                }
                            }
                            HttpURLConnection.HTTP_NOT_FOUND -> {
                                Result.failure(Exception("Token không tồn tại hoặc đã hết hạn"))
                            }
                            HttpURLConnection.HTTP_UNAUTHORIZED -> {
                                Result.failure(Exception("Token không hợp lệ"))
                            }
                            HttpURLConnection.HTTP_FORBIDDEN -> {
                                Result.failure(Exception("Token đã hết hạn"))
                            }
                            HttpURLConnection.HTTP_INTERNAL_ERROR -> {
                                Result.failure(Exception("Lỗi server, vui lòng thử lại sau"))
                            }
                            else -> {
                                Result.failure(Exception("Lỗi kết nối (HTTP $responseCode): $responseText"))
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
            } catch (e: IOException) {
                Result.failure(Exception("Lỗi kết nối mạng: ${e.message}"))
            } catch (e: Exception) {
                Result.failure(Exception("Lỗi không xác định: ${e.message}"))
            }
        }
    }

    /**
     * Extract profile URL from API response
     * First tries to parse as JSON, then falls back to text search
     */
    private fun extractProfileUrl(response: String): String? {
        // Try parsing as JSON first
        try {
            val json = JSONObject(response)
            
            // Check common JSON keys
            val possibleKeys = listOf(
                "url", "subscribe_url", "subscription_url", "link"
            )
            
            for (key in possibleKeys) {
                if (json.has(key)) {
                    val url = json.getString(key)
                    if (url.isNotBlank() && isValidUrl(url)) {
                        return url
                    }
                }
            }
            
            // Check nested data object
            if (json.has("data") && json.getJSONObject("data") != null) {
                val dataObj = json.getJSONObject("data")
                for (key in possibleKeys) {
                    if (dataObj.has(key)) {
                        val url = dataObj.getString(key)
                        if (url.isNotBlank() && isValidUrl(url)) {
                            return url
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // JSON parsing failed, continue to text search
        }

        // Fallback: search for any valid URL in the response text
        val matcher = URL_PATTERN.matcher(response)
        while (matcher.find()) {
            val url = matcher.group()
            if (isValidUrl(url)) {
                return url
            }
        }

        return null
    }

    /**
     * Check if URL is valid and likely a profile URL
     */
    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }
}
