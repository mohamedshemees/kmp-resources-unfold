package com.github.mohamedshemees.kmpresourcesunfold.lint

import com.github.mohamedshemees.kmpresourcesunfold.StringResourceProcessor
import com.github.mohamedshemees.kmpresourcesunfold.inspection.UnusedResourceDetector
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

class UnusedStringInspection : LocalInspectionTool() {

    override fun getStaticDescription(): String {
        return "Detects string resource keys in strings.xml that appear to be unused in the project."
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file.virtualFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (UnusedResourceDetector.isBuildOrIgnoredFile(file)) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        if (file.extension?.lowercase() != "xml" || file.parent?.name?.startsWith("values") != true) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                if (tag.name != "string") return

                val nameAttr = tag.getAttribute("name") ?: return
                val key = nameAttr.value ?: return
                if (key.isBlank()) return

                val isUsed = UnusedResourceDetector.isResourceUsed(holder.project, key, file)
                if (!isUsed) {
                    val highlightElement = nameAttr.nameElement ?: tag
                    holder.registerProblem(
                        highlightElement,
                        "String resource '$key' appears to be unused in this project",
                        DeleteUnusedStringQuickFix(key, file)
                    )
                }
            }
        }
    }

    private class DeleteUnusedStringQuickFix(
        private val resourceKey: String,
        private val declaringFile: VirtualFile
    ) : LocalQuickFix {
        override fun getName(): String = "Safe delete '$resourceKey' across all locales"
        override fun getFamilyName(): String = "Safe delete unused resource"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val relatedFiles = mutableListOf(declaringFile)
            relatedFiles.addAll(StringResourceProcessor.findRelatedLocalizedFiles(declaringFile))

            val psiManager = PsiManager.getInstance(project)

            WriteCommandAction.runWriteCommandAction(project) {
                for (vFile in relatedFiles) {
                    try {
                        val psiFile = psiManager.findFile(vFile) as? XmlFile ?: continue
                        val rootTag = psiFile.rootTag ?: continue
                        val stringTags = rootTag.findSubTags("string")
                        for (stringTag in stringTags) {
                            if (stringTag.getAttributeValue("name") == resourceKey) {
                                stringTag.delete()
                            }
                        }
                    } catch (_: Throwable) {}
                }

                for (vFile in relatedFiles) {
                    VfsUtil.markDirtyAndRefresh(true, true, true, vFile)
                }
                UnusedResourceDetector.clearCache()
            }
        }
    }
}
