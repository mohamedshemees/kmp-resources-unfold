package com.github.mohamedshemees.kmpresourcesunfold.lint

import com.github.mohamedshemees.kmpresourcesunfold.inspection.UnusedResourceDetector
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtVisitorVoid

class KotlinUnusedImageVectorInspection : LocalInspectionTool() {

    override fun getStaticDescription(): String {
        return "Detects Compose Multiplatform ImageVector properties that appear to be unused in the project."
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (UnusedResourceDetector.isBuildOrIgnoredFile(file)) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        if (file.extension?.lowercase() != "kt") {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        return object : KtVisitorVoid() {
            override fun visitProperty(property: KtProperty) {
                super.visitProperty(property)
                val propName = property.name ?: return
                if (propName.startsWith("_")) return // Backing property
                val typeText = property.typeReference?.text ?: ""
                if (!typeText.contains("ImageVector")) return

                val nameIdentifier = property.nameIdentifier ?: return
                val isUsed = UnusedResourceDetector.isResourceUsed(property.project, propName, file)
                if (!isUsed) {
                    holder.registerProblem(
                        nameIdentifier,
                        "Compose ImageVector '$propName' appears to be unused in this project",
                        DeleteUnusedQuickFix(propName, property.containingFile)
                    )
                }
            }
        }
    }

    private class DeleteUnusedQuickFix(private val resourceName: String, private val file: PsiFile) : LocalQuickFix {
        override fun getName(): String = "Safe delete '$resourceName'"
        override fun getFamilyName(): String = "Safe delete unused resource"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val vFile = file.virtualFile ?: return
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                try {
                    vFile.delete(this)
                    com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(true, true, true, vFile.parent)
                } catch (_: Throwable) {}
            }
        }
    }
}
