package com.github.mohamedshemees.kmpresourcesunfold.inspection

import com.github.mohamedshemees.kmpresourcesunfold.ResourceConstants
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.jetbrains.kotlin.psi.KtProperty
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object UnusedResourceDetector {

    private val cache = ConcurrentHashMap<String, Boolean>()
    private val scanGeneration = AtomicInteger(0)

    fun clearCache() {
        cache.clear()
    }

    fun isBuildOrIgnoredFile(file: VirtualFile): Boolean {
        val path = file.path
        return path.contains(ResourceConstants.BUILD_DIR_1) ||
                path.contains(ResourceConstants.BUILD_DIR_2) ||
                path.contains("/.gradle/") ||
                path.contains("\\.gradle\\") ||
                path.contains("/.idea/") ||
                path.contains("\\.idea\\") ||
                path.contains("/.git/") ||
                path.contains("\\.git\\") ||
                path.contains("/generated/") ||
                path.contains("\\generated\\")
    }

    /**
     * Checks if a resource (drawable, string, font, or Kotlin ImageVector) is used in the project.
     * Uses IntelliJ's fast Trigram Word Index for sub-millisecond lookup.
     */
    fun isResourceUsed(
        project: Project,
        resourceName: String,
        declaringFile: VirtualFile? = null
    ): Boolean {
        if (resourceName.isBlank()) return true
        if (DumbService.isDumb(project)) return true
        
        val cleanName = resourceName.removePrefix("_")
        val cacheKey = "${project.basePath}_${declaringFile?.path ?: ""}_$cleanName"
        cache[cacheKey]?.let { return it }

        var isUsed = false
        val searchHelper = PsiSearchHelper.getInstance(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val searchContext = (UsageSearchContext.IN_CODE.toInt() or UsageSearchContext.IN_STRINGS.toInt() or UsageSearchContext.IN_PLAIN_TEXT.toInt()).toShort()

        // Try primary name
        searchHelper.processElementsWithWord(
            { psiElement: PsiElement, _: Int ->
                val containingFile = psiElement.containingFile?.virtualFile
                if (containingFile == null || isBuildOrIgnoredFile(containingFile)) {
                    return@processElementsWithWord true // Skip build artifacts
                }

                // If element is inside the declaring file itself, check if it's just the definition
                if (declaringFile != null && containingFile.path == declaringFile.path) {
                    if (containingFile.extension?.lowercase() == "kt") {
                        val prop = PsiTreeUtil.getParentOfType(psiElement, KtProperty::class.java)
                        if (prop != null && (prop.name == cleanName || prop.name == "_$cleanName" || prop.name?.removePrefix("_") == cleanName)) {
                            val isCall = psiElement.parent is org.jetbrains.kotlin.psi.KtValueArgument ||
                                         psiElement.parent is org.jetbrains.kotlin.psi.KtCallExpression
                            if (!isCall) {
                                return@processElementsWithWord true // Skip self definition
                            }
                        }
                    } else {
                        return@processElementsWithWord true // Skip self in XML/asset
                    }
                }

                // Check for string definitions across ANY values*/strings.xml file
                if (containingFile.extension?.lowercase() == "xml" && containingFile.parent?.name?.startsWith("values") == true) {
                    val xmlTag = PsiTreeUtil.getParentOfType(psiElement, XmlTag::class.java)
                    if (xmlTag != null && xmlTag.name == "string") {
                        val nameAttr = xmlTag.getAttribute("name")
                        if (nameAttr?.value == cleanName) {
                            return@processElementsWithWord true // Skip definition in this or sibling localized file
                        }
                    }
                }

                // Legitimate usage found in code, strings, or layouts!
                isUsed = true
                false // Stop search immediately
            },
            projectScope,
            cleanName,
            searchContext,
            true
        )

        // For drawables, also check snake_case vs camelCase if applicable
        if (!isUsed && cleanName.contains("_")) {
            val camelCase = cleanName.split("_").mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar { it.uppercase() } }.joinToString("")
            if (camelCase != cleanName) {
                searchHelper.processElementsWithWord(
                    { psiElement: PsiElement, _: Int ->
                        val containingFile = psiElement.containingFile?.virtualFile
                        if (containingFile == null || isBuildOrIgnoredFile(containingFile)) {
                            return@processElementsWithWord true
                        }
                        if (declaringFile != null && containingFile.path == declaringFile.path) {
                            return@processElementsWithWord true
                        }
                        if (containingFile.extension?.lowercase() == "xml" && containingFile.parent?.name?.startsWith("values") == true) {
                            val xmlTag = PsiTreeUtil.getParentOfType(psiElement, XmlTag::class.java)
                            if (xmlTag != null && xmlTag.name == "string") {
                                val nameAttr = xmlTag.getAttribute("name")
                                if (nameAttr?.value == cleanName || nameAttr?.value == camelCase) {
                                    return@processElementsWithWord true
                                }
                            }
                        }
                        isUsed = true
                        false
                    },
                    projectScope,
                    camelCase,
                    searchContext,
                    true
                )
            }
        }

        cache[cacheKey] = isUsed
        return isUsed
    }

    /**
     * Batch scans a collection of resources asynchronously and returns the set of unused names/paths.
     */
    fun scanUnusedAsync(
        project: Project,
        items: List<Pair<String, VirtualFile?>>,
        onComplete: (Set<String>) -> Unit
    ) {
        if (project.isDisposed) return

        if (DumbService.isDumb(project)) {
            DumbService.getInstance(project).runWhenSmart {
                if (!project.isDisposed) {
                    scanUnusedAsync(project, items, onComplete)
                }
            }
            return
        }

        val gen = scanGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed || gen != scanGeneration.get()) return@executeOnPooledThread

            val unusedSet = mutableSetOf<String>()
            for ((name, file) in items) {
                if (project.isDisposed || gen != scanGeneration.get()) return@executeOnPooledThread

                if (DumbService.isDumb(project)) {
                    DumbService.getInstance(project).runWhenSmart {
                        if (!project.isDisposed && gen == scanGeneration.get()) {
                            scanUnusedAsync(project, items, onComplete)
                        }
                    }
                    return@executeOnPooledThread
                }

                val used = try {
                    ApplicationManager.getApplication().runReadAction<Boolean> {
                        if (project.isDisposed || DumbService.isDumb(project)) {
                            true
                        } else {
                            isResourceUsed(project, name, file)
                        }
                    }
                } catch (_: IndexNotReadyException) {
                    DumbService.getInstance(project).runWhenSmart {
                        if (!project.isDisposed && gen == scanGeneration.get()) {
                            scanUnusedAsync(project, items, onComplete)
                        }
                    }
                    return@executeOnPooledThread
                } catch (_: Throwable) {
                    true
                }

                if (!used) {
                    unusedSet.add(name)
                    file?.let { unusedSet.add(it.path) }
                }
            }

            if (!project.isDisposed && gen == scanGeneration.get()) {
                javax.swing.SwingUtilities.invokeLater {
                    if (!project.isDisposed && gen == scanGeneration.get()) {
                        onComplete(unusedSet)
                    }
                }
            }
        }
    }
}
