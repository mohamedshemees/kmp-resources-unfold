package com.github.mohamedshemees.kmpresourcesunfold.font

import com.github.mohamedshemees.kmpresourcesunfold.settings.FontSettings
import com.google.gson.Gson
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

object FontCacheManager {
    private val LOG = Logger.getInstance(FontCacheManager::class.java)
    private val gson = Gson()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private const val CACHE_SUBDIR = "kmp-resources-unfold"
    private const val CACHE_FILE_NAME = "google-fonts.json"
    private const val PREVIEWS_SUBDIR = "previews"
    private const val FONTS_SUBDIR = "fonts"
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

    private fun getCacheDir(): File {
        val dir = File(PathManager.getSystemPath(), CACHE_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getPreviewsDir(): File {
        val dir = File(getCacheDir(), PREVIEWS_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getFontsDir(): File {
        val dir = File(getCacheDir(), FONTS_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getFontListFile(sort: String = "popularity"): File {
        return File(getCacheDir(), "google-fonts-$sort.json")
    }

    fun isCacheExpired(sort: String = "popularity"): Boolean {
        val file = getFontListFile(sort)
        if (!file.exists()) return true
        val age = System.currentTimeMillis() - file.lastModified()
        return age > CACHE_TTL_MS
    }

    fun getCachedFontList(sort: String = "popularity"): GoogleWebfontList? {
        val file = getFontListFile(sort)
        if (!file.exists()) {
            return null
        }
        return try {
            val json = file.readText()
            gson.fromJson(json, GoogleWebfontList::class.java)
        } catch (e: Exception) {
            LOG.error("Failed to read cached font list for sort $sort", e)
            null
        }
    }

    fun validateApiKey(key: String): Boolean {
        val url = "https://www.googleapis.com/webfonts/v1/webfonts?family=Roboto&key=$key"
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            val success = response.statusCode() == 200
            success
        } catch (e: Exception) {
            LOG.warn("Failed to validate Google Fonts API Key", e)
            false
        }
    }

    fun fetchAndCacheFontList(key: String, sort: String = "popularity"): GoogleWebfontList? {
        val url = "https://www.googleapis.com/webfonts/v1/webfonts?key=$key&sort=$sort"
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val json = response.body()
                val file = getFontListFile(sort)
                file.writeText(json)
                gson.fromJson(json, GoogleWebfontList::class.java)
            } else {
                LOG.warn("Failed to fetch font list ($sort): HTTP ${response.statusCode()}")
                null
            }
        } catch (e: Exception) {
            LOG.error("Network error during font list fetching ($sort)", e)
            null
        }
    }

    fun fetchVariableFontDetails(key: String, familyName: String): GoogleWebfontItem? {
        val encodedFamily = java.net.URLEncoder.encode(familyName, "UTF-8")
        val url = "https://www.googleapis.com/webfonts/v1/webfonts?key=$key&family=$encodedFamily&capability=VF"
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val list = gson.fromJson(response.body(), GoogleWebfontList::class.java)
                list.items.firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            LOG.error("Failed to fetch variable font details for $familyName", e)
            null
        }
    }

    fun getOrDownloadPreviewFont(familyName: String, menuUrl: String): File? {
        val hash = familyName.lowercase().replace(" ", "_")
        val destFile = File(getPreviewsDir(), "${hash}_preview.ttf")
        if (destFile.exists()) {
            return destFile
        }

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(menuUrl))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() == 200) {
                Files.copy(response.body(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                destFile
            } else {
                null
            }
        } catch (e: Exception) {
            LOG.warn("Failed to download preview font for $familyName from $menuUrl", e)
            null
        }
    }

    fun downloadFontFile(downloadUrl: String, familyName: String, variant: String): File? {
        val sanitizedFamily = familyName.lowercase().replace(" ", "_")
        val destFile = File(getFontsDir(), "${sanitizedFamily}_${variant}.ttf")
        if (destFile.exists()) {
            return destFile
        }

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() == 200) {
                Files.copy(response.body(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                destFile
            } else {
                LOG.warn("Failed to download font: HTTP ${response.statusCode()}")
                null
            }
        } catch (e: Exception) {
            LOG.error("Failed to download font file for $familyName ($variant) from $downloadUrl", e)
            null
        }
    }
}
