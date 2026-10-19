package com.github.mohamedshemees.kmpresourcesunfold.projectView

import com.github.mohamedshemees.kmpresourcesunfold.KotlinImageVectorParser
import com.github.mohamedshemees.kmpresourcesunfold.ResourceConstants
import com.github.mohamedshemees.kmpresourcesunfold.ResourceExtension
import com.github.mohamedshemees.kmpresourcesunfold.inspection.UnusedResourceDetector
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color

class ResourceProjectViewNodeDecorator : ProjectViewNodeDecorator {

    companion object {
        private val UNUSED_ATTRIBUTES = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_SMALLER,
            JBColor(Color(0xD97706), Color(0xFBBF24))
        )
    }

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project: Project = node.project ?: return
        val virtualFile: VirtualFile = node.virtualFile ?: return
        if (virtualFile.isDirectory) return

        // Ensure we are decorating the file node itself, not members/classes inside the file
        if (node.value !is PsiFileSystemItem) return

        if (UnusedResourceDetector.isBuildOrIgnoredFile(virtualFile)) return

        if (!isResourceFile(virtualFile)) return

        // Skip during indexing
        if (DumbService.isDumb(project)) return

        val resourceName = virtualFile.nameWithoutExtension
        val isUsed = UnusedResourceDetector.isResourceUsed(project, resourceName, virtualFile)
        if (!isUsed) {
            val currentText = data.presentableText ?: virtualFile.name
            if (data.coloredText.isEmpty()) {
                data.addText(currentText, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
            data.addText("  Unused", UNUSED_ATTRIBUTES)
            data.tooltip = "Unused resource: '$resourceName' is not referenced in the project"
        }
    }

    private fun isResourceFile(file: VirtualFile): Boolean {
        val path = file.path
        val ext = file.extension?.lowercase() ?: return false

        // 1. Kotlin ImageVector files:
        // Must be in an icons directory AND contain ImageVector definition
        if (ext == "kt") {
            val isInIconsDir = path.contains("/icons/") || path.contains("\\icons\\")
            if (!isInIconsDir) return false
            return try {
                val content = String(file.contentsToByteArray(), Charsets.UTF_8)
                KotlinImageVectorParser.isKotlinImageVectorFile(content)
            } catch (_: Throwable) {
                false
            }
        }

        // 2. XML files:
        // Must be in a drawable directory and not in values/ or a manifest/layout
        if (ext == "xml") {
            val isDrawableDir = path.contains("/drawable") || path.contains("\\drawable")
            val isValuesDir = path.contains("/values") || path.contains("\\values")
            return isDrawableDir && !isValuesDir
        }

        // 3. Image files (PNG, WEBP, JPG, JPEG, SVG):
        val isImage = ResourceExtension.imageExtensions.any { it.extension == ext } || ext == "svg"
        if (isImage) {
            return isResourceLocation(path)
        }

        // 4. Font files (TTF, OTF, WOFF, WOFF2):
        val isFont = ResourceExtension.fontExtensions.any { it.extension == ext }
        if (isFont) {
            return isResourceLocation(path)
        }

        return false
    }

    private fun isResourceLocation(path: String): Boolean {
        return path.contains(ResourceConstants.COMPOSE_RESOURCES_DIR) ||
                path.contains("/src/main/${ResourceConstants.ANDROID_RES_DIR}/") ||
                path.contains("\\src\\main\\${ResourceConstants.ANDROID_RES_DIR}\\") ||
                path.contains("/src/androidMain/${ResourceConstants.ANDROID_RES_DIR}/") ||
                path.contains("\\src\\androidMain\\${ResourceConstants.ANDROID_RES_DIR}\\") ||
                path.contains("/res/") ||
                path.contains("\\res\\") ||
                path.contains("/assets/") ||
                path.contains("\\assets\\") ||
                path.contains("/images/") ||
                path.contains("\\images\\") ||
                path.contains("/fonts/") ||
                path.contains("\\fonts\\")
    }
}
