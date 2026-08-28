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

    private val vectorCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<com.github.mohamedshemees.kmpresourcesunfold.KotlinImageVectorIcon>>>()

    private fun findKotlinVector(
        element: PsiElement,
        iconName: String
    ): Pair<VirtualFile?, com.github.mohamedshemees.kmpresourcesunfold.KotlinImageVectorIcon>? {
        val cleanName = iconName.removePrefix("_")
        // 1. Check current containing file first (in-memory, live editor changes)
        try {
            val containingFile = element.containingFile
            val currentText = containingFile?.text
            if (currentText != null && com.github.mohamedshemees.kmpresourcesunfold.KotlinImageVectorParser.isKotlinImageVectorFile(currentText)) {
                val icons = com.github.mohamedshemees.kmpresourcesunfold.KotlinImageVectorParser.parseKotlinImageVectors(currentText)
                val match = icons.find { 
                    it.name == iconName || 
                    it.name.equals(iconName, ignoreCase = true) ||
                    it.propertyName == iconName ||
                    it.propertyName.equals(iconName, ignoreCase = true) ||
                    it.name.equals(cleanName, ignoreCase = true) ||
                    it.propertyName.equals(cleanName, ignoreCase = true)
                }
                if (match != null) {
                    return Pair(containingFile.virtualFile, match)
                }
            }
        } catch (_: Throwable) {}

        // 2. Check project-wide files
        return findKotlinVectorInProject(element.project, iconName)
    }

    private fun findKotlinVectorInProject(
        project: com.intellij.openapi.project.Project, 
        iconName: String
    ): Pair<VirtualFile, com.github.mohamedshemees.kmpresourcesunfold.KotlinImageVectorIcon>? {
        val cleanName = iconName.removePrefix("_")
        val allFiles = KmpResourceScanner.findComposeDrawables(project)
        for (file in allFiles) {
            if (file.extension?.lowercase() == "kt") {
                val modStamp = file.modificationStamp
                val cached = vectorCache[file.path]
                val icons = if (cached != null && cached.first == modStamp) {
                    cached.second
                } else {
                    try {
                        val content = com.intellij.openapi.vfs.VfsUtilCore.loadText(file)
                        val parsed = com.github.mohamedshemees.kmpresourcesunfold.KotlinImageVectorParser.parseKotlinImageVectors(content)
                        vectorCache[file.path] = Pair(modStamp, parsed)
                        parsed
                    } catch (_: Throwable) { emptyList() }
                }
                val match = icons.find { 
                    it.name == iconName || 
                    it.name.equals(iconName, ignoreCase = true) ||
                    it.propertyName == iconName ||
                    it.propertyName.equals(iconName, ignoreCase = true) ||
                    it.name.equals(cleanName, ignoreCase = true) ||
                    it.propertyName.equals(cleanName, ignoreCase = true)
                }
                if (match != null) return Pair(file, match)
            }
        }
        return null
    }

    private fun createVectorIcon(xmlContent: String?, fallbackFile: VirtualFile?, size: Int = 18): javax.swing.Icon {
        if (xmlContent != null) {
            try {
                val renderResolution = maxOf(size * 4, 64)
                val targetSize = com.android.ide.common.vectordrawable.VdPreview.TargetSize.createFromMaxDimension(renderResolution)
                val img = com.android.ide.common.vectordrawable.VdPreview.getPreviewFromVectorXml(targetSize, xmlContent, StringBuilder())
                if (img != null) {
                    return ResourceIconProvider.CheckerboardIcon(img, size, size)
                }
            } catch (_: Throwable) {}
        }
        return fallbackFile?.let { ResourceIconProvider.getIcon(it, size) }
            ?: ResourceIconProvider.CheckerboardIcon(javax.swing.UIManager.getIcon("FileView.fileIcon"), size, size)
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Quick guard clause: Only inspect leaf elements
        if (element.firstChild != null) return null

        val text = element.text
        if (text.isBlank() || text.length < 2) return null

        val parent = element.parent ?: return null
        val grandParent = parent.parent

        val nameRef = when (element) {
            is KtNameReferenceExpression -> element
            else -> parent as? KtNameReferenceExpression
        }
        val nameRefParent = nameRef?.parent ?: grandParent

        // 1. Handle Drawables (Res.drawable.key)
        if (nameRefParent is KtDotQualifiedExpression && nameRefParent.text.startsWith("Res.drawable.")) {
            if (nameRefParent.selectorExpression == nameRef || nameRefParent.selectorExpression == element) {
                val project = element.project
                val allFiles = KmpResourceScanner.findComposeDrawables(project)
                val matchingFile = allFiles.find { it.nameWithoutExtension == text } ?: return null
                val icon = ResourceIconProvider.getIcon(matchingFile, 18)

                return createMarker(element, icon, "Click to swap drawable: $text") { e, elt ->
                    val targetRef = when (elt) {
                        is KtNameReferenceExpression -> elt
                        else -> elt.parent as? KtNameReferenceExpression
                    }
                    if (targetRef != null && targetRef.parent is KtDotQualifiedExpression) {
                        showSwapPopup(e, targetRef.parent as KtDotQualifiedExpression)
                    }
                }
            }
        }

        // 2. Handle Strings (Res.string.key)
        if (nameRefParent is KtDotQualifiedExpression && nameRefParent.text.startsWith("Res.string.")) {
            if (nameRefParent.selectorExpression == nameRef || nameRefParent.selectorExpression == element) {
                val key = text
                val icon = com.intellij.icons.AllIcons.Actions.Preview
                return createMarker(element, icon, "Click to swap string: $key") { e, elt ->
                    val targetRef = when (elt) {
                        is KtNameReferenceExpression -> elt
                        else -> elt.parent as? KtNameReferenceExpression
                    }
                    if (targetRef != null && targetRef.parent is KtDotQualifiedExpression) {
                        showStringSwapPopup(e, targetRef.parent as KtDotQualifiedExpression)
                    }
                }
            }
        }

        // 3. Handle Fonts (Res.font.key)
        if (nameRefParent is KtDotQualifiedExpression && nameRefParent.text.startsWith("Res.font.")) {
            if (nameRefParent.selectorExpression == nameRef || nameRefParent.selectorExpression == element) {
                val key = text
                val project = element.project
                val allFiles = KmpResourceScanner.findComposeFonts(project)
                val matchingFile = allFiles.find { it.nameWithoutExtension == key } ?: return null
                
                val icon = FontPreviewIcon(matchingFile)
                val fontName = registerCustomFont(matchingFile) ?: key.replace("_", " ")
                val fontStyle = if (fontName.isNotEmpty()) "font-family: '$fontName';" else ""
                val tooltip = "<html><body style='padding: 5px; margin: 0;'><span style=\"$fontStyle font-size: 16px;\">AaBbCc - $fontName</span></body></html>"

                return createMarker(element, icon, tooltip) { e, elt ->
                    val targetRef = when (elt) {
                        is KtNameReferenceExpression -> elt
                        else -> elt.parent as? KtNameReferenceExpression
                    }
                    if (targetRef != null && targetRef.parent is KtDotQualifiedExpression) {
                        showFontSwapPopup(e, targetRef.parent as KtDotQualifiedExpression)
                    }
                }
            }
        }

        // 4. Handle Kotlin ImageVector property definitions (val StickyNote: ImageVector)
        if (parent is org.jetbrains.kotlin.psi.KtProperty && parent.nameIdentifier == element) {
            // Never place gutter icon on backing fields like `_StickyNote`
            if (!text.startsWith("_")) {
                val typeText = parent.typeReference?.text
                val isImageVectorProp = typeText?.contains("ImageVector") == true || 
                                        parent.text.contains("ImageVector.Builder") || 
                                        parent.text.contains("Builder(")
                if (isImageVectorProp) {
                    val found = findKotlinVector(element, text)
                    if (found != null && (found.second.name.equals(text, ignoreCase = true) || found.second.propertyName.equals(text, ignoreCase = true))) {
                        val icon = createVectorIcon(found.second.xmlContent, found.first, 18)
                        return createMarker(element, icon, "Kotlin ImageVector: $text") { _, _ -> }
                    }
                }
            }
        }

        // 5. Handle Vector callers / references e.g. Icon(StickyNote), Image(StickyNote), rememberVectorPainter(StickyNote)
        if (nameRef != null && !text.startsWith("_") && text != "ImageVector" && text != "Builder") {
            val enclosingProp = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, org.jetbrains.kotlin.psi.KtProperty::class.java)
            val isInsideOwnVectorDeclaration = enclosingProp != null && (
                enclosingProp.name == text ||
                enclosingProp.name == "_$text" ||
                enclosingProp.name?.removePrefix("_") == text
            )

            if (!isInsideOwnVectorDeclaration) {
                val valueArg = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, org.jetbrains.kotlin.psi.KtValueArgument::class.java)
                val dotQualified = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, org.jetbrains.kotlin.psi.KtDotQualifiedExpression::class.java)
                val isAppropriateUsage = valueArg != null || (dotQualified != null && dotQualified.selectorExpression == nameRef) || (enclosingProp != null && enclosingProp.name != text)

                if (isAppropriateUsage) {
                    val found = findKotlinVector(element, text)
                    if (found != null && (found.second.name.equals(text, ignoreCase = true) || found.second.propertyName.equals(text, ignoreCase = true))) {
                        val icon = createVectorIcon(found.second.xmlContent, found.first, 18)
                        return createMarker(element, icon, "Click to swap vector: $text") { e, elt ->
                            val targetRef = when (elt) {
                                is KtNameReferenceExpression -> elt
                                else -> elt.parent as? KtNameReferenceExpression
                            }
                            if (targetRef != null) {
                                showVectorSwapPopup(e, targetRef)
                            }
                        }
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

    private fun showFontSwapPopup(e: MouseEvent, expression: KtDotQualifiedExpression) {
        val project = expression.project
        val module = ModuleUtilCore.findModuleForPsiElement(expression) ?: return
        val allFiles = KmpResourceScanner.findComposeFonts(project)
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
                label.icon = FontPreviewIcon(file)
                return label
            }
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(moduleFiles)
            .setTitle("Swap Font")
            .setRenderer(list.cellRenderer)
            .setItemChosenCallback { selectedFile ->
                WriteCommandAction.runWriteCommandAction(project) {
                    val factory = KtPsiFactory(project)
                    val newExpression = factory.createExpression("Res.font.${selectedFile.nameWithoutExtension}")
                    expression.replace(newExpression)
                }
            }
            .setNamerForFiltering { it.nameWithoutExtension }
            .createPopup()
            .show(com.intellij.ui.awt.RelativePoint(e))
    }

    private fun showVectorSwapPopup(e: MouseEvent, expression: KtNameReferenceExpression) {
        val project = expression.project
        val module = ModuleUtilCore.findModuleForPsiElement(expression) ?: return
        val allFiles = KmpResourceScanner.findComposeDrawables(project)
        val moduleFiles = allFiles.filter { file ->
            ModuleUtilCore.findModuleForFile(file, project) == module
        }

        data class VectorOption(val name: String, val file: VirtualFile, val icon: javax.swing.Icon)

        val options = mutableListOf<VectorOption>()
        moduleFiles.forEach { file ->
            if (file.extension?.lowercase() == "kt") {
                try {
                    val content = String(file.contentsToByteArray(), Charsets.UTF_8)
                    val icons = com.github.mohamedshemees.kmpresourcesunfold.KotlinImageVectorParser.parseKotlinImageVectors(content)
                    icons.forEach { iconItem ->
                        val targetSize = com.android.ide.common.vectordrawable.VdPreview.TargetSize.createFromMaxDimension(24)
                        val img = com.android.ide.common.vectordrawable.VdPreview.getPreviewFromVectorXml(targetSize, iconItem.xmlContent, StringBuilder())
                        val swingIcon = img?.let { ResourceIconProvider.CheckerboardIcon(javax.swing.ImageIcon(it), 24, 24) } ?: ResourceIconProvider.getIcon(file, 24)
                        options.add(VectorOption(iconItem.propertyName ?: iconItem.name, file, swingIcon))
                    }
                } catch (_: Throwable) {}
            } else {
                options.add(VectorOption(file.nameWithoutExtension, file, ResourceIconProvider.getIcon(file, 24)))
            }
        }

        if (options.isEmpty()) return

        val sortedOptions = options.sortedBy { it.name }
        val list = JBList(sortedOptions)
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as javax.swing.JLabel
                val option = value as VectorOption
                label.text = option.name
                label.icon = option.icon
                return label
            }
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(sortedOptions)
            .setTitle("Swap Vector / ImageVector")
            .setRenderer(list.cellRenderer)
            .setItemChosenCallback { selectedOption ->
                WriteCommandAction.runWriteCommandAction(project) {
                    val factory = KtPsiFactory(project)
                    val newExpression = factory.createExpression(selectedOption.name)
                    expression.replace(newExpression)
                }
            }
            .setNamerForFiltering { it.name }
            .createPopup()
            .show(com.intellij.ui.awt.RelativePoint(e))
    }
}

private fun registerCustomFont(file: VirtualFile): String? {
    return try {
        val font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, java.io.File(file.path))
        val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        ge.registerFont(font)
        font.fontName
    } catch (e: Exception) {
        null
    }
}

private class FontPreviewIcon(private val fontFile: VirtualFile) : javax.swing.Icon {
    private var loadedFont: java.awt.Font? = null
    
    init {
        try {
            loadedFont = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, java.io.File(fontFile.path)).deriveFont(12f)
        } catch (e: Exception) {}
    }

    override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics?, x: Int, y: Int) {
        val g2d = g as? java.awt.Graphics2D ?: return
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        
        val fontToUse = loadedFont ?: c?.font?.deriveFont(12f) ?: java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12)
        g2d.font = fontToUse
        g2d.color = com.intellij.ui.JBColor.foreground()
        
        g2d.drawString("Aa", x + 1, y + 13)
    }

    override fun getIconWidth(): Int = 18
    override fun getIconHeight(): Int = 18
}
