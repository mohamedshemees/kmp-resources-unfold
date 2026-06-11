package com.github.mohamedshemees.kmpresourcesunfold.navigation

import com.intellij.openapi.editor.Editor
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty

class ResourceDocumentationTargetProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val element = file.findElementAt(offset) ?: return emptyList()
        val module = com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(file)
        
        // 1. Check for Drawables
        val drawableKey = getResourceKey(element, "Res.drawable.")
        if (drawableKey != null) {
            return listOf(ResourceDocumentationTarget(drawableKey, file.project, ResourceDocumentationTarget.ResourceType.DRAWABLE, module))
        }

        // 2. Check for Strings
        val stringKey = getResourceKey(element, "Res.string.")
        if (stringKey != null) {
            return listOf(ResourceDocumentationTarget(stringKey, file.project, ResourceDocumentationTarget.ResourceType.STRING, module))
        }
        
        return emptyList()
    }

    private fun getResourceKey(element: PsiElement?, prefix: String): String? {
        if (element == null) return null
        
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            val text = current.text
            
            if (current is KtDotQualifiedExpression) {
                if (text.contains(prefix)) {
                    val key = text.substringAfter(prefix).substringBefore(".")
                    if (key.isNotEmpty() && key != prefix.substringBeforeLast(".").substringAfterLast(".")) return key
                }
            }
            
            if (current is KtNameReferenceExpression) {
                val p = current.parent
                if (p is KtDotQualifiedExpression && p.text.contains(prefix)) {
                    val key = p.text.substringAfter(prefix).substringBefore(".")
                    if (key.isNotEmpty()) return key
                }
            }

            if (current is KtProperty) {
                if (text.startsWith("internal val $prefix")) {
                    return text.substringAfter(prefix).substringBefore(":").substringBefore("=").trim()
                }
            }
            current = current.parent
        }
        return null
    }
}
