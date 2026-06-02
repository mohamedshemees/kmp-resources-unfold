package com.github.mohamedshemees.kmpresourcesunfold.navigation

import com.github.mohamedshemees.kmpresourcesunfold.*
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty

class ResourceDocumentationTarget(
    private val key: String,
    private val project: Project
) : DocumentationTarget {

    override fun computeDocumentation(): DocumentationResult? {
        val matchingFile = findResourceFile(project, key) ?: return null
        val imageHtml = ResourceIconProvider.getIconAsHtml(matchingFile, 200) ?: return null

        val html = """
            <div style="font-family: sans-serif; padding: 10px;">
                <div style="margin-bottom: 10px; font-weight: bold; font-size: 14px; color: #569CD6;">$key</div>
                <div style="background-color: #80808022; border: 1px solid #80808044; display: inline-block; padding: 10px; border-radius: 4px;">
                    $imageHtml
                </div>
                <div style="margin-top: 12px; color: #808080; font-size: 12px; line-height: 1.5;">
                    <b style="color: #9CDCFE;">Format:</b> ${matchingFile.extension?.uppercase()}<br>
                    <b style="color: #9CDCFE;">Path:</b> ${matchingFile.parent?.name}/${matchingFile.name}<br>
                    <b style="color: #9CDCFE;">Size:</b> ${ResourceUtils.formatSize(matchingFile.length)}
                </div>
            </div>
        """.trimIndent()
        
        return DocumentationResult.documentation(html)
    }

    override fun createPointer() = com.intellij.model.Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation {
        return TargetPresentation.builder("Resource: $key").presentation()
    }

    private fun findResourceFile(project: Project, key: String): VirtualFile? {
        val extensions = listOf("xml", "png", "jpg", "jpeg", "webp", "svg")
        for (ext in extensions) {
            val fileName = "$key.$ext"
            val files = FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
            val resFile = files.find { file ->
                val path = file.path.replace("\\", "/")
                path.contains("composeResources") || path.contains("/res/") || path.contains("/assets/")
            }
            if (resFile != null) return resFile
        }
        return null
    }
}
