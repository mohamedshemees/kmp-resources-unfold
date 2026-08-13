package com.github.mohamedshemees.kmpresourcesunfold

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

object KmpResourceScanner {
    fun findComposeDrawables(project: Project): List<VirtualFile> {
        val drawableFiles = mutableListOf<VirtualFile>()
        ProjectRootManager.getInstance(project).fileIndex.iterateContent { file ->
            if (!file.isDirectory) {
                val path = file.path
                val ext = file.extension?.lowercase()
                val isKotlinSource = ext == "kt" && (path.contains("/src/") || path.contains("\\src\\"))
                val isComposeResource = path.contains(ResourceConstants.COMPOSE_RESOURCES_DIR)
                val isAndroidResource = path.contains("/src/main/${ResourceConstants.ANDROID_RES_DIR}/") || 
                                     path.contains("\\src\\main\\${ResourceConstants.ANDROID_RES_DIR}\\") ||
                                     path.contains("/src/androidMain/${ResourceConstants.ANDROID_RES_DIR}/") || 
                                     path.contains("\\src\\androidMain\\${ResourceConstants.ANDROID_RES_DIR}\\")
                val isGenericAsset = path.contains("/assets/") || path.contains("\\assets\\") ||
                                    path.contains("/images/") || path.contains("\\images\\")
                
                if ((isKotlinSource || isComposeResource || isAndroidResource || isGenericAsset) && 
                    !path.contains(ResourceConstants.BUILD_DIR_1) && 
                    !path.contains(ResourceConstants.BUILD_DIR_2)) {
                    if (ext == "kt") {
                        try {
                            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
                            if (KotlinImageVectorParser.isKotlinImageVectorFile(content)) {
                                drawableFiles.add(file)
                            }
                        } catch (_: Throwable) {}
                    } else if (ResourceExtension.allExtensions.contains(ext)) {
                        drawableFiles.add(file)
                    }
                }
            }
            true
        }
        return drawableFiles.distinctBy { it.path }
    }
    fun findComposeFonts(project: Project): List<VirtualFile> {
        val fontFiles = mutableListOf<VirtualFile>()
        ProjectRootManager.getInstance(project).fileIndex.iterateContent { file ->
            if (!file.isDirectory) {
                val path = file.path
                val isComposeResource = path.contains(ResourceConstants.COMPOSE_RESOURCES_DIR)
                val isAndroidResource = path.contains("/src/main/${ResourceConstants.ANDROID_RES_DIR}/") || 
                                     path.contains("\\src\\main\\${ResourceConstants.ANDROID_RES_DIR}\\") ||
                                     path.contains("/src/androidMain/${ResourceConstants.ANDROID_RES_DIR}/") || 
                                     path.contains("\\src\\androidMain\\${ResourceConstants.ANDROID_RES_DIR}\\")
                
                if ((isComposeResource || isAndroidResource) && 
                    !path.contains(ResourceConstants.BUILD_DIR_1) && 
                    !path.contains(ResourceConstants.BUILD_DIR_2)) {
                    val ext = file.extension?.lowercase()
                    if (ext == "ttf" || ext == "otf" || ext == "woff" || ext == "woff2") {
                        fontFiles.add(file)
                    }
                }
            }
            true
        }
        return fontFiles.distinctBy { it.path }
    }
}
