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
                val isComposeResource = path.contains(ResourceConstants.COMPOSE_RESOURCES_DIR)
                val isAndroidResource = path.contains("/src/main/${ResourceConstants.ANDROID_RES_DIR}/") || 
                                     path.contains("\\src\\main\\${ResourceConstants.ANDROID_RES_DIR}\\") ||
                                     path.contains("/src/androidMain/${ResourceConstants.ANDROID_RES_DIR}/") || 
                                     path.contains("\\src\\androidMain\\${ResourceConstants.ANDROID_RES_DIR}\\")
                val isGenericAsset = path.contains("/assets/") || path.contains("\\assets\\") ||
                                    path.contains("/images/") || path.contains("\\images\\")
                
                if ((isComposeResource || isAndroidResource || isGenericAsset) && 
                    !path.contains(ResourceConstants.BUILD_DIR_1) && 
                    !path.contains(ResourceConstants.BUILD_DIR_2)) {
                    val ext = file.extension?.lowercase()
                    if (ResourceExtension.allExtensions.contains(ext)) {
                        drawableFiles.add(file)
                    }
                }
            }
            true
        }
        return drawableFiles.distinctBy { it.path }
    }
}
