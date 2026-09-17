package com.github.mohamedshemees.kmpresourcesunfold.projectView

import com.github.mohamedshemees.kmpresourcesunfold.ResourceExtension
import com.github.mohamedshemees.kmpresourcesunfold.inspection.UnusedResourceDetector
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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

        if (UnusedResourceDetector.isBuildOrIgnoredFile(virtualFile)) return

        val ext = virtualFile.extension?.lowercase() ?: return
        val isResource = ResourceExtension.allExtensions.contains(ext) || 
            (ext == "kt" && (virtualFile.path.contains("/icons/") || virtualFile.path.contains("\\icons\\")))

        if (!isResource) return

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
}
