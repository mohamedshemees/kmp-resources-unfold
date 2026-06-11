package com.github.mohamedshemees.kmpresourcesunfold.navigation

import com.github.mohamedshemees.kmpresourcesunfold.KmpResourceScanner
import com.github.mohamedshemees.kmpresourcesunfold.ResourceIconProvider
import com.github.mohamedshemees.kmpresourcesunfold.StringResource
import com.github.mohamedshemees.kmpresourcesunfold.StringResourceProcessor
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
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

class ResourcePreviewLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is KtNameReferenceExpression) return null

        val text = element.text
        val parent = element.parent

        // 1. Handle Drawables (Res.drawable.key)
        if (parent is KtDotQualifiedExpression && parent.text.startsWith("Res.drawable.")) {
            if (parent.selectorExpression == element) {
                val key = text
                val project = element.project
                val allFiles = KmpResourceScanner.findComposeDrawables(project)
                val matchingFile = allFiles.find { it.nameWithoutExtension == key } ?: return null
                val icon = ResourceIconProvider.getIcon(matchingFile, 18)

                return createMarker(element, icon, "Click to swap drawable: $key") { e, elt ->
                    if (elt is KtNameReferenceExpression && elt.parent is KtDotQualifiedExpression) {
                        showSwapPopup(e, elt.parent as KtDotQualifiedExpression)
                    }
                }
            }
        }

        // 2. Handle Strings (Res.string.key)
        if (parent is KtDotQualifiedExpression && parent.text.startsWith("Res.string.")) {
            if (parent.selectorExpression == element) {
                val key = text
                val icon = com.intellij.icons.AllIcons.Actions.Preview
                return createMarker(element, icon, "Click to swap string: $key") { e, elt ->
                    if (elt is KtNameReferenceExpression && elt.parent is KtDotQualifiedExpression) {
                        showStringSwapPopup(e, elt.parent as KtDotQualifiedExpression)
                    }
                }
            }
        }

        return null
    }

    private fun createMarker(
        element: PsiElement,
        icon: javax.swing.Icon,
        tooltip: String,
        navHandler: GutterIconNavigationHandler<PsiElement>
    ): LineMarkerInfo<PsiElement> {
        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { tooltip },
            navHandler,
            GutterIconRenderer.Alignment.LEFT,
            { tooltip }
        )
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
                val label = super.getListCellRendererComponent(
                    list,
                    value,
                    index,
                    isSelected,
                    cellHasFocus
                ) as javax.swing.JLabel
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

    private fun showStringSwapPopup(e: MouseEvent, expression: KtDotQualifiedExpression) {
        val project = expression.project
        val module = ModuleUtilCore.findModuleForPsiElement(expression) ?: return

        val allFiles = KmpResourceScanner.findComposeDrawables(project)
        val moduleStringFiles = allFiles.filter { file ->
            file.name == com.github.mohamedshemees.kmpresourcesunfold.ResourceConstants.STRINGS_FILE &&
            ModuleUtilCore.findModuleForFile(file, project) == module
        }
        val moduleStrings = StringResourceProcessor.findStringResources(project, moduleStringFiles)
        val sortedStrings = moduleStrings.sortedBy { it.key }

        if (sortedStrings.isEmpty()) return

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(sortedStrings)
            .setTitle("Swap String Resource")
            .setRenderer(object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean       
                ): Component {
                    val label = super.getListCellRendererComponent(
                        list,
                        value,
                        index,
                        isSelected,
                        cellHasFocus
                    ) as javax.swing.JLabel
                    val resource = value as StringResource
                    label.text = "${resource.key} (\"${resource.defaultValue}\")"
                    label.icon = com.intellij.icons.AllIcons.Actions.Preview
                    return label
                }
            })
            .setItemChosenCallback { selectedResource ->
                WriteCommandAction.runWriteCommandAction(project) {
                    val factory = KtPsiFactory(project)
                    val newExpression = factory.createExpression("Res.string.${selectedResource.key}")
                    expression.replace(newExpression)
                }
            }
            .setNamerForFiltering { it.key }
            .createPopup()
            .show(com.intellij.ui.awt.RelativePoint(e))
    }
}
