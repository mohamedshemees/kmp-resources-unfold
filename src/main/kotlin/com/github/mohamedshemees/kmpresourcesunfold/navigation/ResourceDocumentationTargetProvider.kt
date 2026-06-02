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
        val key = getResourceKey(element) ?: return emptyList()
        
        return listOf(ResourceDocumentationTarget(key, file.project))
    }

    private fun getResourceKey(element: PsiElement?): String? {
        if (element == null) return null
        
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            val text = current.text
            
            // Handle usages like Res.drawable.male
            if (current is KtDotQualifiedExpression) {
                if (text.contains("Res.drawable.")) {
                    val key = text.substringAfter("Res.drawable.").substringBefore(".")
                    if (key.isNotEmpty() && key != "drawable") return key
                }
            }
            
            // Handle leaf reference expressions
            if (current is KtNameReferenceExpression) {
                val parent = current.parent
                if (parent is KtDotQualifiedExpression && parent.text.contains("Res.drawable.")) {
                    val fullText = parent.text
                    val key = fullText.substringAfter("Res.drawable.").substringBefore(".")
                    if (key.isNotEmpty() && key != "drawable") return key
                }
            }

            // Handle property declarations
            if (current is KtProperty) {
                val fqName = current.fqName?.asString() ?: ""
                if (fqName.contains("Res.drawable") || text.startsWith("internal val Res.drawable.")) {
                    val key = text.substringAfter("Res.drawable.").substringBefore(":").substringBefore("=").trim()
                    if (key.isNotEmpty() && key != "drawable") return key
                }
            }
            current = current.parent
        }
        return null
    }
}
