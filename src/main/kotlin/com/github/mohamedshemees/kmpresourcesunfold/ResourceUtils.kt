package com.github.mohamedshemees.kmpresourcesunfold

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.util.Locale
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

    fun getTargetResourceDir(module: Module, project: Project): VirtualFile? {
        val contentRoots = ModuleRootManager.getInstance(module).contentRoots
        val moduleRoot = contentRoots.find { it.path.contains("src/commonMain") }
            ?: contentRoots.filter { !it.path.contains("/build/") && !it.path.contains("\\build\\") }.firstOrNull()
            ?: project.guessProjectDir() ?: return null

        val targetPath = if (moduleRoot.path.contains("src/commonMain")) {
            "composeResources/drawable"
        } else {
            "src/commonMain/composeResources/drawable"
        }
        
        return com.intellij.openapi.vfs.VfsUtil.createDirectoryIfMissing(moduleRoot, targetPath)
    }
}
