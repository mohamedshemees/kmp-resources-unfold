package com.github.mohamedshemees.kmpresourcesunfold.navigation

import com.github.mohamedshemees.kmpresourcesunfold.KmpResourceScanner
import com.github.mohamedshemees.kmpresourcesunfold.ResourceIconProvider
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBList
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

class ResourcePreviewLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is KtDotQualifiedExpression) return null

        val text = element.text
        if (text.startsWith("Res.drawable.")) {
            val key = text.substringAfter("Res.drawable.")
            if (key.isEmpty() || key.contains(".")) return null

            val project = element.project
            val allFiles = KmpResourceScanner.findComposeDrawables(project)
            val matchingFile = allFiles.find { it.nameWithoutExtension == key } ?: return null

            val icon = ResourceIconProvider.getIcon(matchingFile, 18)

            return LineMarkerInfo(
                element,
                element.textRange,
                icon,
                { "Click to swap drawable: $key" },
                object : GutterIconNavigationHandler<PsiElement> {
                    override fun navigate(e: MouseEvent, elt: PsiElement) {
                        if (elt !is KtDotQualifiedExpression) return
                        showSwapPopup(e, elt)
                    }
                },
                GutterIconRenderer.Alignment.LEFT,
                { "Preview: $key" }
            )
        }

        return null
    }

    private fun showSwapPopup(e: MouseEvent, expression: KtDotQualifiedExpression) {
        val project = expression.project
        val module = ModuleUtilCore.findModuleForPsiElement(expression) ?: return
        
        val allFiles = KmpResourceScanner.findComposeDrawables(project)
        val moduleFiles = allFiles.filter { file ->
            ModuleUtilCore.findModuleForFile(file, project) == module
        }.sortedBy { it.nameWithoutExtension }

        if (moduleFiles.isEmpty()) return

        val list = JBList(moduleFiles)
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as javax.swing.JLabel
                val file = value as VirtualFile
                label.text = file.nameWithoutExtension
                label.icon = ResourceIconProvider.getIcon(file, 24)
                return label
            }
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(moduleFiles)
            .setTitle("Swap Drawable")
            .setRenderer(list.cellRenderer)
            .setItemChosenCallback { selectedFile ->
                WriteCommandAction.runWriteCommandAction(project) {
                    val factory = KtPsiFactory(project)
                    val newExpression = factory.createExpression("Res.drawable.${selectedFile.nameWithoutExtension}")
                    expression.replace(newExpression)
                }
            }
            .setNamerForFiltering { it.nameWithoutExtension }
            .createPopup()
            .show(com.intellij.ui.awt.RelativePoint(e))
    }
}
