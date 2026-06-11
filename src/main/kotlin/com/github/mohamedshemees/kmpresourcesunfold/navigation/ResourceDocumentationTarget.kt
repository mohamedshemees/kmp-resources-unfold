package com.github.mohamedshemees.kmpresourcesunfold.navigation

import com.github.mohamedshemees.kmpresourcesunfold.*
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

class ResourceDocumentationTarget(
    private val key: String,
    private val project: Project,
    private val type: ResourceType,
    private val module: com.intellij.openapi.module.Module? = null
) : DocumentationTarget {

    enum class ResourceType { DRAWABLE, STRING }

    override fun computeDocumentation(): DocumentationResult? {
        return when (type) {
            ResourceType.DRAWABLE -> computeDrawableDoc()
            ResourceType.STRING -> computeStringDoc()
        }
    }

    private fun computeDrawableDoc(): DocumentationResult? {
        val matchingFile = findResourceFile(project, key) ?: return null
        val imageHtml = ResourceIconProvider.getIconAsHtml(matchingFile, 200) ?: return null

        val html = """
            <div style="font-family: sans-serif; padding: 10px;">
                <div style="margin-bottom: 10px; font-weight: bold; font-size: 14px; color: #569CD6;">$key</div>
                <div style="display: inline-block;">
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

    private fun computeStringDoc(): DocumentationResult? {
        val defaultFile = if (module != null) {
            StringResourceProcessor.getDefaultStringResourceFileForModule(module, key)
        } else {
            val stringFiles = FilenameIndex.getVirtualFilesByName(ResourceConstants.STRINGS_FILE, GlobalSearchScope.projectScope(project))
            stringFiles.filter { it.parent.name == ResourceConstants.VALUES_DIR }.find { file ->
                StringResourceProcessor.parseStrings(project, file).containsKey(key)
            }
        } ?: return null

        val missingIn = StringResourceProcessor.getMissingTranslations(project, defaultFile, key)
        val translations = mutableListOf<String>()
        val related = StringResourceProcessor.findRelatedLocalizedFiles(defaultFile)
        
        val defaultStrings = StringResourceProcessor.parseStrings(project, defaultFile)
        defaultStrings[key]?.let { translations.add("<b>Default:</b> $it") }

        related.forEach { file ->
            val strings = StringResourceProcessor.parseStrings(project, file)
            val value = strings[key]
            if (value != null) {
                val locale = file.parent.name.substringAfter("-", "default")
                translations.add("<b>$locale:</b> $value")
            }
        }

        val html = """
            <div style="font-family: sans-serif; padding: 10px;">
                <div style="margin-bottom: 10px; font-weight: bold; font-size: 14px; color: #569CD6;">String: $key</div>
                <div style="margin-top: 5px; line-height: 1.6;">
                    ${translations.joinToString("<br/>")}
                </div>
                ${if (missingIn.isNotEmpty()) "<div style='margin-top: 10px; color: #F44336;'><b>Missing:</b> ${missingIn.joinToString(", ")}</div>" else ""}
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
