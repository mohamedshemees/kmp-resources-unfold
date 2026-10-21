package com.github.mohamedshemees.kmpresourcesunfold

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile

data class StringResource(
    val key: String,
    val defaultValue: String,
    val missingLocales: List<String>,
    val file: VirtualFile,
    val moduleName: String = "Unknown",
    val isUnused: Boolean = false
)

object StringResourceProcessor {

    fun findStringResources(
        project: Project,
        files: List<VirtualFile>,
        fileToModuleMap: Map<VirtualFile, String> = emptyMap()
    ): List<StringResource> {
        val stringFiles = files.filter { it.name == ResourceConstants.STRINGS_FILE }
        val defaultStringsFiles = stringFiles.filter { it.parent.name == ResourceConstants.VALUES_DIR }
        
        val resources = mutableListOf<StringResource>()

        for (defaultFile in defaultStringsFiles) {
            val defaultStrings = parseStrings(project, defaultFile)
            val moduleName = fileToModuleMap[defaultFile] ?: ModuleUtilCore.findModuleForFile(defaultFile, project)?.name ?: "Unknown"

            for ((key, value) in defaultStrings) {
                val missing = getMissingTranslations(project, defaultFile, key)
                resources.add(StringResource(key, value, missing, defaultFile, moduleName))
            }
        }

        return resources
    }

    fun findRelatedLocalizedFiles(currentFile: VirtualFile): List<VirtualFile> {
        val parentDir = currentFile.parent ?: return emptyList()
        val grandParentDir = parentDir.parent ?: return emptyList()

        val isValidParent = grandParentDir.name.contains(ResourceConstants.COMPOSE_RESOURCES_DIR) || 
                          grandParentDir.name == ResourceConstants.ANDROID_RES_DIR

        if (!isValidParent) return emptyList()

        val baseDirName = parentDir.name.substringBefore("-")
        val fileName = currentFile.name

        return grandParentDir.children
            .filter { it.isDirectory && (it.name == baseDirName || it.name.startsWith("$baseDirName-")) }
            .mapNotNull { it.findChild(fileName) }
            .filter { it.path != currentFile.path }
    }

    fun getMissingTranslations(project: Project, currentFile: VirtualFile, key: String): List<String> {
        val relatedFiles = findRelatedLocalizedFiles(currentFile)
        val missingIn = mutableListOf<String>()

        val psiManager = PsiManager.getInstance(project)
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

    fun getMissingTranslationsByKey(project: Project, key: String): List<String> {
        val stringFiles = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(ResourceConstants.STRINGS_FILE, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
        val defaultFile = stringFiles.find { file ->
            file.parent.name == ResourceConstants.VALUES_DIR && parseStrings(project, file).containsKey(key)
        } ?: return emptyList()

        return getMissingTranslations(project, defaultFile, key)
    }

    fun getDefaultStringResourceFileForModule(module: com.intellij.openapi.module.Module, key: String): VirtualFile? {
        val project = module.project
        val allStringFiles = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(
            ResourceConstants.STRINGS_FILE,
            com.intellij.psi.search.GlobalSearchScope.projectScope(project)
        )
        
        val candidateFiles = allStringFiles.filter { file ->
            file.parent.name == ResourceConstants.VALUES_DIR && parseStrings(project, file).containsKey(key)
        }
        
        if (candidateFiles.isEmpty()) return null
        
        // 1. Prioritize files in the current module
        candidateFiles.find { com.intellij.openapi.module.ModuleUtilCore.findModuleForFile(it, project) == module }?.let { return it }
        
        // 2. Then prioritize files in dependencies
        val scope = com.intellij.psi.search.GlobalSearchScope.moduleWithDependenciesScope(module)
        candidateFiles.find { scope.contains(it) }?.let { return it }
        
        return candidateFiles.firstOrNull()
    }

    fun getMissingTranslationsForModule(module: com.intellij.openapi.module.Module, key: String): List<String> {
        val defaultFile = getDefaultStringResourceFileForModule(module, key) ?: return emptyList()
        return getMissingTranslations(module.project, defaultFile, key)
    }

    fun parseStrings(project: Project, file: VirtualFile): Map<String, String> {

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
