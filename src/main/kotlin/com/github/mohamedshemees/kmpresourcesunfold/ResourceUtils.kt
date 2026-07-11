package com.github.mohamedshemees.kmpresourcesunfold

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.util.*
import kotlin.math.ln

object ResourceUtils {

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return MyBundle.message("unit.bytes", bytes.toString())
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return MyBundle.message(
            "unit.size",
            String.format(Locale.US, "%.1f", bytes / Math.pow(1024.0, exp.toDouble())),
            pre.toString()
        )
    }

    fun getTargetResourceDir(module: Module): VirtualFile? {
        val resDir = getComposeResourcesDir(module) ?: return null
        return com.intellij.openapi.vfs.VfsUtil.createDirectoryIfMissing(resDir, "drawable")
    }

    fun getComposeResourcesDir(module: Module): VirtualFile? {
        val contentRoots = ModuleRootManager.getInstance(module).contentRoots
        for (root in contentRoots) {
            // Strictly look for resources under src/
            com.intellij.openapi.vfs.VfsUtil.findRelativeFile(root, "src", "commonMain", "composeResources")?.let { return it }
            com.intellij.openapi.vfs.VfsUtil.findRelativeFile(root, "src", "main", "composeResources")?.let { return it }
        }
        return null
    }

    fun getAndroidResourcesDir(module: Module): VirtualFile? {
        val contentRoots = ModuleRootManager.getInstance(module).contentRoots
        for (root in contentRoots) {
            // Strictly look for resources under src/
            com.intellij.openapi.vfs.VfsUtil.findRelativeFile(root, "src", "androidMain", ResourceConstants.ANDROID_RES_DIR)?.let { return it }
            com.intellij.openapi.vfs.VfsUtil.findRelativeFile(root, "src", "main", ResourceConstants.ANDROID_RES_DIR)?.let { return it }
        }
        return null
    }

    fun getAllResourceDirs(module: Module): List<Pair<String, VirtualFile>> {
        val dirs = mutableListOf<Pair<String, VirtualFile>>()
        getComposeResourcesDir(module)?.let { dirs.add("Compose" to it) }
        getAndroidResourcesDir(module)?.let { dirs.add("Android" to it) }
        return dirs
    }

    fun isFlutterProject(project: Project): Boolean {
        val projectDir = project.guessProjectDir() ?: return false
        return projectDir.findChild("pubspec.yaml") != null
    }

    fun getOrCreateResourceDir(module: Module?, project: Project, targetType: String = "Compose"): VirtualFile? {
        val existing = when (targetType) {
            "Android" -> module?.let { getAndroidResourcesDir(it) }
            "Compose" -> module?.let { getComposeResourcesDir(it) }
            "Assets" -> {
                val root = (module?.let { ModuleRootManager.getInstance(it).contentRoots.firstOrNull() } ?: project.guessProjectDir()) ?: return null
                com.intellij.openapi.vfs.VfsUtil.findRelativeFile(root, "assets")
            }
            else -> null
        }
        if (existing != null) return existing

        val root = (module?.let { ModuleRootManager.getInstance(it).contentRoots.firstOrNull { r -> !r.path.contains("/build/") && !r.path.contains("\\build\\") } } 
            ?: project.guessProjectDir()) ?: return null
            
        val relativePath = when (targetType) {
            "Android" -> {
                val androidMain = com.intellij.openapi.vfs.VfsUtil.findRelativeFile(root, "src", "androidMain")
                if (androidMain != null) "src/androidMain/${ResourceConstants.ANDROID_RES_DIR}" else "src/main/${ResourceConstants.ANDROID_RES_DIR}"
            }
            "Assets" -> "assets"
            "Compose" -> {
                val commonMain = com.intellij.openapi.vfs.VfsUtil.findRelativeFile(root, "src", "commonMain")
                if (commonMain != null) "src/commonMain/${ResourceConstants.COMPOSE_RESOURCES_DIR}" else ResourceConstants.COMPOSE_RESOURCES_DIR
            }
            else -> targetType // Treat as direct relative path
        }
        
        var result: VirtualFile? = null
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            result = com.intellij.openapi.vfs.VfsUtil.createDirectoryIfMissing(root, relativePath)
        }
        return result
    }

    fun findBasePackage(module: Module): String {
        val rootManager = ModuleRootManager.getInstance(module)
        val sourceRoots = rootManager.sourceRoots
        for (root in sourceRoots) {
            val path = root.path
            if (path.contains("src/commonMain/kotlin") || path.contains("src\\commonMain\\kotlin")) {
                val basePackage = findPackageRecursively(root, "")
                if (basePackage.isNotEmpty()) return basePackage
            }
        }
        for (root in sourceRoots) {
            val basePackage = findPackageRecursively(root, "")
            if (basePackage.isNotEmpty()) return basePackage
        }
        return ""
    }

    private fun findPackageRecursively(dir: VirtualFile, currentPackage: String): String {
        val children = dir.children ?: return currentPackage
        val subDirs = children.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" }
        if (subDirs.size == 1) {
            val subDir = subDirs.first()
            val nextPackage = if (currentPackage.isEmpty()) subDir.name else "$currentPackage.${subDir.name}"
            return findPackageRecursively(subDir, nextPackage)
        }
        return currentPackage
    }

    fun generateTypographyFile(
        module: Module,
        destinationFolder: java.io.File,
        packageName: String,
        fontFamilies: Map<String, List<Pair<String, String>>> // familyName to list of (fileName, variant)
    ): java.io.File? {
        if (!destinationFolder.exists()) {
            destinationFolder.mkdirs()
        }

        val sb = StringBuilder()
        sb.append("package $packageName\n\n")
        sb.append("import androidx.compose.ui.text.font.FontFamily\n")
        sb.append("import androidx.compose.ui.text.font.Font\n")
        sb.append("import androidx.compose.ui.text.font.FontWeight\n")
        sb.append("import androidx.compose.ui.text.font.FontStyle\n")
        sb.append("\n")

        fontFamilies.forEach { (familyName, fontFiles) ->
            val camelFamilyName = familyName.replace(" ", "").replace(Regex("[^a-zA-Z0-9]"), "")
            sb.append("val ${camelFamilyName}FontFamily = FontFamily(\n")

            fontFiles.forEachIndexed { index, (fileName, variant) ->
                val resourceName = fileName.substringBeforeLast(".").lowercase()
                val weight = when {
                    variant.contains("100") -> "FontWeight.Thin"
                    variant.contains("200") -> "FontWeight.ExtraLight"
                    variant.contains("300") -> "FontWeight.Light"
                    variant.contains("500") -> "FontWeight.Medium"
                    variant.contains("600") -> "FontWeight.SemiBold"
                    variant.contains("700") || variant.contains("bold") -> "FontWeight.Bold"
                    variant.contains("800") -> "FontWeight.ExtraBold"
                    variant.contains("900") -> "FontWeight.Black"
                    else -> "FontWeight.Normal"
                }
                val style = if (variant.contains("italic")) "FontStyle.Italic" else "FontStyle.Normal"
                val comma = if (index < fontFiles.size - 1) "," else ""
                sb.append("    Font(Res.font.$resourceName, $weight, $style)$comma\n")
            }
            sb.append(")\n\n")
        }

        val file = java.io.File(destinationFolder, "Typography.kt")
        file.writeText(sb.toString().trimEnd() + "\n")
        return file
    }
}
