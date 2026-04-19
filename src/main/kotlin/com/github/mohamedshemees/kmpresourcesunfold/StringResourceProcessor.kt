package com.github.mohamedshemees.kmpresourcesunfold

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.PsiManager

data class StringResource(
    val key: String,
    val defaultValue: String,
    val missingLocales: List<String>,
    val file: VirtualFile
)

object StringResourceProcessor {

    fun findStringResources(project: Project, files: List<VirtualFile>): List<StringResource> {
        val stringFiles = files.filter { it.name == ResourceConstants.STRINGS_FILE }
        val defaultStringsFiles = stringFiles.filter { it.parent.name == ResourceConstants.VALUES_DIR }
        
        val resources = mutableListOf<StringResource>()

        for (defaultFile in defaultStringsFiles) {
            val defaultStrings = parseStrings(project, defaultFile)

            for ((key, value) in defaultStrings) {
                val missing = getMissingTranslations(project, defaultFile, key)
                resources.add(StringResource(key, value, missing, defaultFile))
            }
        }

        return resources
    }

    fun findRelatedLocalizedFiles(currentFile: VirtualFile): List<VirtualFile> {
        val parentDir = currentFile.parent ?: return emptyList()
        val grandParentDir = parentDir.parent ?: return emptyList()

        if (!grandParentDir.name.contains(ResourceConstants.COMPOSE_RESOURCES_DIR)) return emptyList()

        val baseDirName = parentDir.name.substringBefore("-")
        val fileName = currentFile.name

        return grandParentDir.children
            .filter { it.isDirectory && (it.name == baseDirName || it.name.startsWith("$baseDirName-")) }
            .mapNotNull { it.findChild(fileName) }
            .filter { it.path != currentFile.path }
    }

    fun getMissingTranslations(project: Project, currentFile: VirtualFile, key: String): List<String> {
        val relatedFiles = findRelatedLocalizedFiles(currentFile)
        if (relatedFiles.isEmpty()) return emptyList()

        val psiManager = PsiManager.getInstance(project)
        val missingIn = mutableListOf<String>()

        for (file in relatedFiles) {
            val psiFile = psiManager.findFile(file) as? XmlFile ?: continue
            val rootTag = psiFile.rootTag ?: continue

            val hasKey = rootTag.findSubTags("string").any {
                it.getAttributeValue("name") == key
            }

            if (!hasKey) {
                val locale = if (file.parent.name.contains("-")) {
                    file.parent.name.substringAfter("-")
                } else {
                    file.parent.name
                }
                missingIn.add(locale)
            }
        }
        return missingIn
    }

    private fun parseStrings(project: Project, file: VirtualFile): Map<String, String> {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? XmlFile ?: return emptyMap()
        val rootTag = psiFile.rootTag ?: return emptyMap()
        
        return rootTag.findSubTags("string").mapNotNull { tag ->
            val name = tag.getAttributeValue("name")
            if (name != null) {
                Pair(name, tag.value.text)
            } else {
                null
            }
        }.toMap().filterKeys { it.isNotEmpty() }
    }

}
