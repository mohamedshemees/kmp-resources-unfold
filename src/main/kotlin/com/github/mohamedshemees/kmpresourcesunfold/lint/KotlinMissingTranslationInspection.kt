package com.github.mohamedshemees.kmpresourcesunfold.lint

import com.github.mohamedshemees.kmpresourcesunfold.StringResourceProcessor
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

class KotlinMissingTranslationInspection : LocalInspectionTool() {

    override fun getStaticDescription(): String {
        return "Detects Compose Multiplatform resource references (Res.string.key) that are missing translations in one or more locales."
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                super.visitDotQualifiedExpression(expression)

                // Looking for Res.string.key
                val text = expression.text
                if (text.startsWith("Res.string.")) {
                    val key = text.substringAfter("Res.string.")
                    if (key.isEmpty() || key.contains(".")) return

                    val project = expression.project
                    val missingIn = StringResourceProcessor.getMissingTranslationsByKey(project, key)

                    if (missingIn.isNotEmpty()) {
                        holder.registerProblem(
                            expression,
                            com.github.mohamedshemees.kmpresourcesunfold.MyBundle.message(
                                "inspection.missingTranslation", 
                                key, 
                                missingIn.joinToString(", ")
                            )
                        )
                    }
                }
            }
        }
    }
}
