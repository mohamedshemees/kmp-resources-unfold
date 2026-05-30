package com.github.mohamedshemees.kmpresourcesunfold

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.util.Locale
import kotlin.math.ln

object ResourceUtils {
    fun String.cleanModuleName(): String {
        val cleaned = this.replace(Regex("(?i)^\\.?mena\\.?"), "")
        return if (cleaned.isEmpty()) this else cleaned
    }

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

    fun getTargetResourceDir(module: Module, project: Project): VirtualFile? {
        val resDir = getComposeResourcesDir(module, project) ?: return null
        return com.intellij.openapi.vfs.VfsUtil.createDirectoryIfMissing(resDir, "drawable")
    }

    fun getComposeResourcesDir(module: Module, project: Project): VirtualFile? {
        val contentRoots = ModuleRootManager.getInstance(module).contentRoots
        for (root in contentRoots) {
            // Strictly look for resources under src/
            com.intellij.openapi.vfs.VfsUtil.findRelativeFile(root, "src", "commonMain", "composeResources")?.let { return it }
            com.intellij.openapi.vfs.VfsUtil.findRelativeFile(root, "src", "main", "composeResources")?.let { return it }
        }
        return null
    }

    fun getAndroidResourcesDir(module: Module, project: Project): VirtualFile? {
        val contentRoots = ModuleRootManager.getInstance(module).contentRoots
        for (root in contentRoots) {
            // Strictly look for resources under src/
            com.intellij.openapi.vfs.VfsUtil.findRelativeFile(root, "src", "androidMain", ResourceConstants.ANDROID_RES_DIR)?.let { return it }
            com.intellij.openapi.vfs.VfsUtil.findRelativeFile(root, "src", "main", ResourceConstants.ANDROID_RES_DIR)?.let { return it }
        }
        return null
    }

    fun getAllResourceDirs(module: Module, project: Project): List<Pair<String, VirtualFile>> {
        val dirs = mutableListOf<Pair<String, VirtualFile>>()
        getComposeResourcesDir(module, project)?.let { dirs.add("Compose" to it) }
        getAndroidResourcesDir(module, project)?.let { dirs.add("Android" to it) }
        return dirs
    }

    fun isFlutterProject(project: Project): Boolean {
        val projectDir = project.guessProjectDir() ?: return false
        return projectDir.findChild("pubspec.yaml") != null
    }

    fun getOrCreateResourceDir(module: Module?, project: Project, targetType: String = "Compose"): VirtualFile? {
        val existing = when (targetType) {
            "Android" -> module?.let { getAndroidResourcesDir(it, project) }
            "Compose" -> module?.let { getComposeResourcesDir(it, project) }
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
}
