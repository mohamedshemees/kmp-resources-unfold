package com.github.mohamedshemees.kmpresourcesunfold.lint

import com.github.mohamedshemees.kmpresourcesunfold.StringResourceProcessor
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag

class MissingTranslationInspection : LocalInspectionTool() {

    override fun getStaticDescription(): String {
        return "Detects string resource definitions in XML that are missing translations in one or more locales."
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                if (tag.name != "string") return

                val nameAttr = tag.getAttribute("name") ?: return
                val key = nameAttr.value ?: return
                if (key.isEmpty()) return

                val currentFile = tag.containingFile.virtualFile ?: return
                val missingIn = StringResourceProcessor.getMissingTranslations(tag.project, currentFile, key)

                if (missingIn.isNotEmpty()) {
                    holder.registerProblem(
                        nameAttr.nameElement,
                        com.github.mohamedshemees.kmpresourcesunfold.MyBundle.message("inspection.missingTranslation", key, missingIn.joinToString(", "))
                    )
                }
            }
        }
    }
}
