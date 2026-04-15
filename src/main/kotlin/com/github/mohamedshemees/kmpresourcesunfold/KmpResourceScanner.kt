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
                if (path.contains(ResourceConstants.COMPOSE_RESOURCES_DIR) && 
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
        return drawableFiles.distinctBy { it.name }
    }
}
