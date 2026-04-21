package com.github.mohamedshemees.kmpresourcesunfold.navigation

import com.github.mohamedshemees.kmpresourcesunfold.StringResourceProcessor
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile

class ResourceLocalizationLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        if (element !is XmlFile || element.rootTag == null) return

        val currentFile = element.virtualFile ?: return
        val relatedFiles = StringResourceProcessor.findRelatedLocalizedFiles(currentFile)

        if (relatedFiles.isNotEmpty()) {
            val builder = NavigationGutterIconBuilder.create(AllIcons.Actions.Diff)
                .setTargets(relatedFiles.map { element.manager.findFile(it)!! })
                .setTooltipText("Navigate to localized versions")
                .setNamer { psiElement ->
                    val file = (psiElement as PsiFile).virtualFile
                    val locale = file.parent.name.substringAfter("-", "default")
                    "Locale: $locale (${file.parent.name})"
                }

            result.add(builder.createLineMarkerInfo(element.rootTag!!))
        }
    }
}
