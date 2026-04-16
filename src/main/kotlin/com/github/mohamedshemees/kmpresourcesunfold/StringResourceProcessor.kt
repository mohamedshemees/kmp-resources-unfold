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
            val psiFile = PsiManager.getInstance(project).findFile(defaultFile) as? XmlFile ?: continue
            
            // Find sibling localization folders (e.g., values-ar)
            val parentDir = defaultFile.parent.parent ?: continue
            val localizedFiles = parentDir.children
                .filter { it.isDirectory && it.name.startsWith("values-") }
                .mapNotNull { it.findChild(ResourceConstants.STRINGS_FILE) }

            val localizedMaps = localizedFiles.associate { file ->
                file.parent.name.substringAfter("values-") to parseStrings(project, file).keys
            }

            val defaultStrings = parseStrings(project, defaultFile)

            for ((key, value) in defaultStrings) {
                val missing = localizedMaps.filter { !it.value.contains(key) }.keys.toList()
                resources.add(StringResource(key, value, missing, defaultFile))
            }
        }

        return resources
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
