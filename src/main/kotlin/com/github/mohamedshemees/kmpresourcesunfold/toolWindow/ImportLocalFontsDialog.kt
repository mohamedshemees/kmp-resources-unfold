package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.*
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import java.io.IOException
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

data class ImportedLocalFontItem(
    val file: VirtualFile,
    private val originalName: String = file.nameWithoutExtension,
    var isSelected: Boolean = true
) {
    var name: String = sanitizeFontFilename(originalName)
    var customPrefixApplied: String = ""

    val extension: String
        get() = file.extension ?: ""

    fun applyPrefix(enabled: Boolean) {
        val stdPrefix = "font_"
        val hasStdPrefix = name.startsWith("font_")
        val restOfName = if (hasStdPrefix) name.substringAfter("font_") else name

        name = if (enabled) {
            "${stdPrefix}${restOfName}"
        } else {
            restOfName
        }
    }

    fun applyCustomPrefix(customPrefix: String) {
        val hasStdPrefix = name.startsWith("font_")
        val stdPrefix = if (hasStdPrefix) "font_" else ""
        
        var restOfName = if (hasStdPrefix) name.substringAfter("font_") else name
        
        if (customPrefixApplied.isNotEmpty() && restOfName.startsWith(customPrefixApplied)) {
            restOfName = restOfName.substring(customPrefixApplied.length)
        }
        
        restOfName = "${customPrefix}${restOfName}"
        customPrefixApplied = customPrefix
        
        name = "${stdPrefix}${restOfName}"
    }

    companion object {
        fun sanitizeFontFilename(original: String): String {
            return original.lowercase()
                .replace(Regex("[^a-z0-9]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
        }
    }
}

class ImportLocalFontsDialog(
    private val project: Project,
    initialFiles: List<VirtualFile>
) : DialogWrapper(project) {

    private enum class ImportStep(val id: String) {
        SELECT_RESOURCES("ImportingFontsStep"),
        CHOOSE_TARGET("ChooseFontImportLocationStep")
    }

    private val importedItems = initialFiles.map { ImportedLocalFontItem(it) }.toMutableList()
    private var currentStep = ImportStep.SELECT_RESOURCES
    private var applyPrefix = false
    private val mainPanel = JPanel(CardLayout())
    private lateinit var step1Panel: JPanel
    private lateinit var step2Panel: JPanel

    private val step1ListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty()
    }
    private val step1ScrollPane = JBScrollPane(step1ListPanel).apply {
        border = JBUI.Borders.empty()
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val targetBox = ComboBox<TargetOption>()
    private val generateKotlinCb = JCheckBox("Generate Compose FontFamily / Typography code")
    private val kotlinPathField = JTextField()
    private val packageNameField = JTextField()
    private data class TargetOption(
        val module: Module?, 
        val targetType: String,
        val file: VirtualFile?,
        val isCustom: Boolean = false
    ) {
        override fun toString(): String {
            val cleanName = module?.name
            return if (cleanName != null) "$cleanName: $targetType" else targetType
        }
    }

    private val previousAction = object : AbstractAction(MyBundle.message("button.previous")) {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            currentStep = ImportStep.SELECT_RESOURCES
            updateStep()
        }
    }

    init {
        title = "Import Fonts"
        init()
        updateStep()
    }

    override fun createCenterPanel(): JComponent {
        mainPanel.preferredSize = Dimension(800, 600)
        step1Panel = createStep1Panel()
        step2Panel = createStep2Panel()

        mainPanel.add(step1Panel, ImportStep.SELECT_RESOURCES.id)
        mainPanel.add(step2Panel, ImportStep.CHOOSE_TARGET.id)

        return mainPanel
    }

    private fun createStep1Panel(): JPanel {
        val panel = JPanel(BorderLayout())

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 10, 5, 10)

            val row1 = JPanel(BorderLayout()).apply {
                isOpaque = false
                val countLabel = JBLabel().apply {
                    font = JBUI.Fonts.label().deriveFont(Font.BOLD)
                }
                add(countLabel, BorderLayout.WEST)

                val rightActionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
                    isOpaque = false

                    val deleteSelected = JButton(MyBundle.message("button.removeSelected"), AllIcons.General.Remove).apply {
                        putClientProperty("JButton.buttonType", "link")
                        addActionListener {
                            val selectedItems = importedItems.filter { it.isSelected }
                            if (selectedItems.isNotEmpty()) {
                                importedItems.removeAll(selectedItems)
                                refreshStep1(panel)
                            }
                        }
                    }
                    add(deleteSelected)

                    val importMore = JButton(MyBundle.message("button.importMore")).apply {
                        putClientProperty("JButton.buttonType", "link")
                        addActionListener {
                            val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
                            descriptor.title = "Select Font Files"
                            descriptor.withExtensionFilter("Font Files (*.ttf, *.otf)", "ttf", "otf")

                            val files = FileChooser.chooseFiles(descriptor, project, null)
                            files.forEach { file ->
                                if (importedItems.none { it.file == file }) {
                                    importedItems.add(ImportedLocalFontItem(file).apply { applyPrefix(applyPrefix) })
                                }
                            }
                            refreshStep1(panel)
                        }
                    }
                    add(importMore)
                }
                add(rightActionsPanel, BorderLayout.EAST)
            }
            add(row1)
            add(Box.createVerticalStrut(8))

            val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                isOpaque = false

                val selectAllCheckbox = JCheckBox(MyBundle.message("prompt.selectAll")).apply {
                    isOpaque = false
                    addActionListener {
                        val selected = isSelected
                        importedItems.forEach { it.isSelected = selected }
                        refreshStep1(panel)
                    }
                }
                add(selectAllCheckbox)

                val prefixCheckbox = JCheckBox("Apply standard prefix (font_)", applyPrefix).apply {
                    isOpaque = false
                    addActionListener {
                        applyPrefix = isSelected
                        importedItems.filter { it.isSelected }.forEach { it.applyPrefix(applyPrefix) }
                        refreshStep1(panel)
                    }
                }
                add(prefixCheckbox)

                add(Box.createHorizontalStrut(15))

                val customPrefixLabel = JBLabel(MyBundle.message("prompt.customPrefix"))
                add(customPrefixLabel)

                val customPrefixField = JTextField(8).apply {
                    minimumSize = Dimension(80, preferredSize.height)
                }
                add(customPrefixField)

                val applyCustomPrefixBtn = JButton(MyBundle.message("button.apply")).apply {
                    addActionListener {
                        val prefixText = customPrefixField.text
                        importedItems.filter { it.isSelected }.forEach { 
                            it.applyCustomPrefix(prefixText) 
                        }
                        refreshStep1(panel)
                    }
                }
                add(applyCustomPrefixBtn)
            }
            add(row2)
        }

        panel.add(header, BorderLayout.NORTH)
        panel.add(step1ScrollPane, BorderLayout.CENTER)

        step1ScrollPane.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val files = support.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<java.io.File>
                val virtualFiles = files.mapNotNull { VirtualFileManager.getInstance().findFileByNioPath(it.toPath()) }
                
                var added = false
                virtualFiles.forEach { file ->
                    val ext = file.extension?.lowercase()
                    if ((ext == "ttf" || ext == "otf") && importedItems.none { it.file == file }) {
                        importedItems.add(ImportedLocalFontItem(file).apply { applyPrefix(applyPrefix) })
                        added = true
                    }
                }
                
                if (added) {
                    refreshStep1(panel)
                }
                return true
            }
        }

        refreshStep1(panel)
        return panel
    }

    private fun refreshStep1(panel: JPanel) {
        val header = panel.getComponent(0) as JPanel
        
        val row1 = header.getComponent(0) as JPanel
        val countLabel = row1.getComponent(0) as JBLabel
        val count = importedItems.size
        countLabel.text = if (count == 1) "Step 1: Select Font to Import" else "Step 1: Select Fonts to Import ($count selected)"

        val rightActionsPanel = row1.getComponent(1) as JPanel
        val deleteSelected = rightActionsPanel.getComponent(0) as JButton
        deleteSelected.isEnabled = importedItems.any { it.isSelected }

        val row2 = header.getComponent(2) as JPanel
        val selectAllCheckbox = row2.getComponent(0) as JCheckBox
        selectAllCheckbox.isSelected = importedItems.isNotEmpty() && importedItems.all { it.isSelected }

        val scrollPos = step1ScrollPane.verticalScrollBar.value

        step1ListPanel.removeAll()

        val groupedItems = importedItems.groupBy { it.name }

        groupedItems.forEach { (name, items) ->
            val itemPanel = createGroupPanel(name, items, isSummary = false) { refreshStep1(panel) }
            itemPanel.maximumSize = Dimension(Int.MAX_VALUE, itemPanel.preferredSize.height)
            step1ListPanel.add(itemPanel)
            val sep = JSeparator()
            sep.maximumSize = Dimension(Int.MAX_VALUE, 1)
            step1ListPanel.add(sep)
        }

        step1ListPanel.add(Box.createVerticalGlue())
        
        step1ListPanel.revalidate()
        step1ListPanel.repaint()
        
        SwingUtilities.invokeLater {
            step1ScrollPane.verticalScrollBar.value = scrollPos
        }
    }

    private fun createGroupPanel(
        name: String, 
        items: List<ImportedLocalFontItem>, 
        isSummary: Boolean,
        onUpdate: () -> Unit
    ): JPanel {
        val groupPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
        }

        val headerPanel = JPanel(BorderLayout(12, 0)).apply {
            border = JBUI.Borders.empty(10, 12, 5, 12)
            isOpaque = false
        }

        if (isSummary) {
            headerPanel.add(JBLabel(name).apply { 
                font = font.deriveFont(Font.BOLD, 14f)
            }, BorderLayout.CENTER)
        } else {
            val nameField = JTextField(name).apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13f)
            }
            nameField.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    val text = nameField.text
                    items.forEach { 
                        val currentPrefix = if (it.name.startsWith("font_")) "font_" else ""
                        it.name = if (applyPrefix && !text.startsWith(currentPrefix)) "$currentPrefix$text" else text
                    }
                    onUpdate()
                }
            })

            val groupCheckbox = JCheckBox().apply {
                isOpaque = false
                isSelected = items.all { it.isSelected }
                addActionListener {
                    val selected = isSelected
                    items.forEach { it.isSelected = selected }
                    onUpdate()
                }
            }
            headerPanel.add(groupCheckbox, BorderLayout.WEST)
            headerPanel.add(nameField, BorderLayout.CENTER)

            val removeAction = object : AnAction(AllIcons.Actions.Close) {
                override fun actionPerformed(e: AnActionEvent) {
                    importedItems.removeAll(items)
                    onUpdate()
                }
            }
            val removeBtn = ActionButton(removeAction, removeAction.templatePresentation.clone(), "KmpResourcesUnfold", Dimension(22, 22))
            headerPanel.add(removeBtn, BorderLayout.EAST)
        }
        
        groupPanel.add(headerPanel, BorderLayout.NORTH)

        val itemsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyLeft(20)
        }

        items.forEach { item ->
            val row = createItemRow(item, isSummary, onUpdate)
            itemsPanel.add(row)
        }
        groupPanel.add(itemsPanel, BorderLayout.CENTER)

        return groupPanel
    }

    private fun createItemRow(
        item: ImportedLocalFontItem, 
        isSummary: Boolean, 
        onUpdate: () -> Unit
    ): JPanel {
        val itemRowPanel = JPanel(BorderLayout(12, 0)).apply {
            border = JBUI.Borders.empty(5, 12)
            isOpaque = false
        }

        val infoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        if (isSummary) {
            val editorText = (targetBox.editor.editorComponent as JTextField).text
            val (_, baseType, _) = parseTargetText(editorText)
            
            val selectedTarget = targetBox.selectedItem as? TargetOption
            val resDirName = selectedTarget?.file?.name ?: when(baseType) {
                "Android" -> "res"
                "Compose" -> "composeResources"
                "Assets" -> "assets"
                else -> baseType.ifEmpty { "composeResources" }
            }
            
            val targetPath = "$resDirName/font/${item.name}.${item.extension}"
            
            val nameLabel = JBLabel(item.name).apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            infoPanel.add(nameLabel)
            
            val pathLabel = JBLabel(targetPath).apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getLabelDisabledForeground()
                alignmentX = Component.LEFT_ALIGNMENT
            }
            infoPanel.add(pathLabel)
        } else {
            val size = ResourceUtils.formatSize(item.file.length)
            val infoLabel = JBLabel("Size: $size, Format: ${item.extension.uppercase()}").apply {
                foreground   = UIUtil.getLabelDisabledForeground()
                font         = JBUI.Fonts.smallFont()
                alignmentX   = Component.LEFT_ALIGNMENT
            }
            infoPanel.add(infoLabel)
        }

        itemRowPanel.add(infoPanel, BorderLayout.CENTER)

        if (!isSummary) {
            val individualRemoveAction = object : AnAction(AllIcons.Actions.Close) {
                override fun actionPerformed(e: AnActionEvent) {
                    importedItems.remove(item)
                    onUpdate()
                }
            }
            val individualRemoveBtn = ActionButton(individualRemoveAction, individualRemoveAction.templatePresentation.clone(), "KmpResourcesUnfold", Dimension(18, 18))
            itemRowPanel.add(individualRemoveBtn, BorderLayout.EAST)
        }

        itemRowPanel.maximumSize = Dimension(Int.MAX_VALUE, itemRowPanel.preferredSize.height)

        return itemRowPanel
    }

    private fun parseTargetText(text: String): Triple<String?, String, String?> {
        val modulePart = if (text.contains(":")) text.substringBefore(":").trim() else null
        val rightPart = if (text.contains(":")) text.substringAfter(":").trim() else text.trim()
        
        val typeSubParts = rightPart.split(".", limit = 2)
        val baseType = typeSubParts[0]
        val subPath = if (typeSubParts.size > 1) typeSubParts[1] else null
        
        return Triple(modulePart, baseType, subPath)
    }

    private fun createStep2Panel(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(20)
        }
        
        targetBox.isEditable = true
        val editor = targetBox.editor.editorComponent as JTextField
        var isFiltering = false
        var lastSelectedTarget: TargetOption? = null
        
        lateinit var allTargetOptions: List<TargetOption>
        
        val updateTargetBox = {
            val selectedBefore = targetBox.selectedItem as? TargetOption
            isFiltering = true
            targetBox.removeAllItems()
            allTargetOptions.forEach {
                targetBox.addItem(it)
            }
            if (selectedBefore != null) {
                val found = allTargetOptions.find { it == selectedBefore }
                if (found != null) {
                    targetBox.selectedItem = found
                    lastSelectedTarget = found
                } else if (targetBox.itemCount > 0) {
                    targetBox.selectedIndex = 0
                    lastSelectedTarget = allTargetOptions[0]
                }
            } else if (targetBox.itemCount > 0) {
                targetBox.selectedIndex = 0
                lastSelectedTarget = allTargetOptions[0]
            }
            isFiltering = false
        }
        
        val optionsList = mutableListOf<TargetOption>()
        if (ResourceUtils.isFlutterProject(project)) {
            val projectDir = project.guessProjectDir()
            projectDir?.children?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { dir ->
                optionsList.add(TargetOption(null, dir.name, dir))
            }
        } else {
            val modules = ModuleManager.getInstance(project).modules.filter { module ->
                val name = module.name.lowercase()
                val hasResources = ResourceUtils.getComposeResourcesDir(module) != null || 
                                   ResourceUtils.getAndroidResourcesDir(module) != null
                !name.contains("ios") && !name.contains("test") && hasResources
            }.sortedBy { it.name }

            modules.forEach { module ->
                val existingTargets = ResourceUtils.getAllResourceDirs(module)
                existingTargets.forEach { (type, file) ->
                    optionsList.add(TargetOption(module, type, file))
                }
                
                com.intellij.openapi.roots.ModuleRootManager.getInstance(module).contentRoots
                    .firstNotNullOfOrNull { com.intellij.openapi.vfs.VfsUtil.findRelativeFile(it, "assets") }
                    ?.let { optionsList.add(TargetOption(module, "Assets", it)) }
            }
        }
        
        allTargetOptions = optionsList

        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filter()
            override fun removeUpdate(e: DocumentEvent) = filter()
            override fun changedUpdate(e: DocumentEvent) = filter()
            
            fun filter() {
                if (isFiltering) return
                SwingUtilities.invokeLater {
                    if (isFiltering) return@invokeLater
                    
                    val text = editor.text
                    val currentSelected = targetBox.selectedItem as? TargetOption
                    val isExactMatch = currentSelected != null && currentSelected.toString() == text
                    
                    if (!isExactMatch) {
                        isFiltering = true
                        val caret = editor.caretPosition
                        targetBox.removeAllItems()
                        val matches = allTargetOptions.filter {
                            it.toString().lowercase().contains(text.lowercase())
                        }
                        matches.forEach { targetBox.addItem(it) }
                        
                        editor.text = text
                        editor.caretPosition = caret
                        if (matches.isNotEmpty() && !targetBox.isPopupVisible && editor.hasFocus()) {
                            targetBox.showPopup()
                        }
                        isFiltering = false
                    }
                    refreshStep2()
                }
            }
        })

        editor.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                if (isFiltering) return
                val text = editor.text
                if (text.isNotEmpty()) {
                    val match = allTargetOptions.find { it.toString() == text }
                    if (match != null) {
                        isFiltering = true
                        targetBox.selectedItem = match
                        isFiltering = false
                    }
                }
            }
        })

        editor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    targetBox.hidePopup()
                    val text = editor.text
                    val match = allTargetOptions.find { it.toString() == text } ?: if (targetBox.itemCount > 0) targetBox.getItemAt(0) else null
                    if (match != null) {
                        isFiltering = true
                        targetBox.selectedItem = match
                        editor.text = match.toString()
                        isFiltering = false
                        if (match != lastSelectedTarget) {
                            lastSelectedTarget = match
                            refreshStep2()
                        }
                    }
                }
            }
        })
        
        targetBox.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) {}
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {
                SwingUtilities.invokeLater {
                    isFiltering = true
                    val currentSelected = targetBox.selectedItem
                    targetBox.removeAllItems()
                    allTargetOptions.forEach { targetBox.addItem(it) }
                    targetBox.selectedItem = currentSelected
                    isFiltering = false
                }
            }
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {}
        })

        updateTargetBox()

        val updateKotlinVisibility = {
            val enabled = generateKotlinCb.isSelected
            kotlinPathField.isEnabled = enabled
            packageNameField.isEnabled = enabled
        }
        generateKotlinCb.isOpaque = false
        generateKotlinCb.addActionListener { updateKotlinVisibility() }
        kotlinPathField.isEditable = false
        packageNameField.isOpaque = true

        val updateKotlinPath = {
            val selectedTarget = targetBox.selectedItem as? TargetOption
            val module = selectedTarget?.module
            if (module != null) {
                val basePackage = ResourceUtils.findBasePackage(module)
                packageNameField.text = if (basePackage.isNotEmpty()) "$basePackage.theme" else "theme"

                val contentRoots = ModuleRootManager.getInstance(module).contentRoots
                val commonMainKotlin = contentRoots.firstNotNullOfOrNull { root ->
                    com.intellij.openapi.vfs.VfsUtil.findRelativeFile(root, "src", "commonMain", "kotlin")
                } ?: contentRoots.firstNotNullOfOrNull { root ->
                    com.intellij.openapi.vfs.VfsUtil.findRelativeFile(root, "src", "main", "kotlin")
                }

                if (commonMainKotlin != null) {
                    val packagePath = if (basePackage.isNotEmpty()) basePackage.replace(".", "/") + "/theme" else "theme"
                    val fullPath = File(commonMainKotlin.path, packagePath).absolutePath
                    kotlinPathField.text = fullPath
                } else {
                    val firstRoot = contentRoots.firstOrNull { r -> !r.path.contains("/build/") && !r.path.contains("\\build\\") }
                    kotlinPathField.text = firstRoot?.let { File(it.path, "src/commonMain/kotlin").absolutePath } ?: ""
                }
            } else {
                kotlinPathField.text = ""
                packageNameField.text = ""
            }
        }

        val kotlinBrowseBtn = JButton("Browse...").apply {
            addActionListener {
                val selectedTarget = targetBox.selectedItem as? TargetOption
                val module = selectedTarget?.module ?: return@addActionListener
                val contentRoots = ModuleRootManager.getInstance(module).contentRoots
                val descriptor = FileChooserDescriptor(false, true, false, false, false, false).apply {
                    title = "Select Kotlin Source Folder"
                    description = "Choose a package directory inside the module content root"
                    setRoots(contentRoots.toList())
                }

                val initialFile = VfsUtil.findFileByIoFile(File(kotlinPathField.text), true)
                    ?: contentRoots.firstOrNull()

                val chosen = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, initialFile)
                if (chosen != null) {
                    kotlinPathField.text = chosen.path

                    val path = chosen.path
                    val kotlinIndex = path.indexOf("/src/commonMain/kotlin/")
                    val kotlinWinIndex = path.indexOf("\\src\\commonMain\\kotlin\\")
                    val index = if (kotlinIndex != -1) {
                        kotlinIndex + "/src/commonMain/kotlin/".length
                    } else if (kotlinWinIndex != -1) {
                        kotlinWinIndex + "\\src\\commonMain\\kotlin\\".length
                    } else {
                        val mainIndex = path.indexOf("/src/main/kotlin/")
                        val mainWinIndex = path.indexOf("\\src\\main\\kotlin\\")
                        if (mainIndex != -1) {
                            mainIndex + "/src/main/kotlin/".length
                        } else if (mainWinIndex != -1) {
                            mainWinIndex + "\\src\\main\\kotlin\\".length
                        } else {
                            -1
                        }
                    }

                    if (index != -1) {
                        val relativePkg = path.substring(index).replace("/", ".").replace("\\", ".")
                        packageNameField.text = relativePkg
                    }
                }
            }
        }
        generateKotlinCb.addActionListener {
            kotlinBrowseBtn.isEnabled = generateKotlinCb.isSelected
        }
        kotlinBrowseBtn.isEnabled = generateKotlinCb.isSelected

        targetBox.addActionListener {
            if (!isFiltering) {
                val selected = targetBox.selectedItem as? TargetOption
                if (selected != null && selected != lastSelectedTarget) {
                    lastSelectedTarget = selected
                    refreshStep2()
                    updateKotlinPath()
                }
            }
        }

        updateKotlinPath()
        updateKotlinVisibility()

        val form = panel {
            row("Target package:") {
                cell(targetBox).align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row {
                cell(generateKotlinCb)
            }
            row("Kotlin Source Folder:") {
                cell(kotlinPathField).align(com.intellij.ui.dsl.builder.AlignX.FILL)
                cell(kotlinBrowseBtn)
            }
            row("Package Name:") {
                cell(packageNameField).align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
        }
        
        panel.add(form, BorderLayout.NORTH)
        
        val summaryPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        
        val scrollPane = JBScrollPane(summaryPanel).apply {
            border = JBUI.Borders.customLine(JBColor.border())
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        if (targetBox.itemCount > 0) {
            targetBox.selectedIndex = 0
        }
        
        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(previousAction, okAction, cancelAction)
    }

    private fun updateStep() {
        val layout = mainPanel.layout as CardLayout
        if (currentStep == ImportStep.SELECT_RESOURCES) {
            layout.show(mainPanel, ImportStep.SELECT_RESOURCES.id)
            setOKButtonText(MyBundle.message("button.next"))
            previousAction.isEnabled = false
        } else {
            layout.show(mainPanel, ImportStep.CHOOSE_TARGET.id)
            setOKButtonText(MyBundle.message("button.import_single"))
            previousAction.isEnabled = true
            refreshStep2()
        }
    }

    private fun refreshStep2() {
        if (!::step2Panel.isInitialized) return
        
        val scrollPane = step2Panel.getComponent(1) as JBScrollPane
        val summaryPanel = scrollPane.viewport.view as JPanel
        summaryPanel.removeAll()
        
        val groupedItems = importedItems.filter { it.isSelected }.groupBy { it.name }

        groupedItems.forEach { (name, items) ->
            val groupPanel = createGroupPanel(name, items, isSummary = true) {}
            groupPanel.maximumSize = Dimension(Int.MAX_VALUE, groupPanel.preferredSize.height)
            summaryPanel.add(groupPanel)
            val sep = JSeparator()
            sep.maximumSize = Dimension(Int.MAX_VALUE, 1)
            summaryPanel.add(sep)
        }
        
        summaryPanel.add(Box.createVerticalGlue())
        summaryPanel.revalidate()
        summaryPanel.repaint()
    }

    override fun doOKAction() {
        if (currentStep == ImportStep.SELECT_RESOURCES) {
            currentStep = ImportStep.CHOOSE_TARGET
            updateStep()
        } else {
            performImport()
            super.doOKAction()
        }
    }

    private fun getFamilyAndVariant(name: String): Pair<String, String> {
        val cleanName = name.removePrefix("font_")
        val variants = listOf(
            "bold_italic", "bolditalic", "bold",
            "medium_italic", "mediumitalic", "medium",
            "light_italic", "lightitalic", "light",
            "thin_italic", "thinitalic", "thin",
            "italic", "regular"
        )

        var variant = "regular"
        var family = cleanName

        for (v in variants) {
            if (cleanName.endsWith("_$v") || cleanName.endsWith(v)) {
                variant = v
                family = if (cleanName.endsWith("_$v")) {
                    cleanName.substringBeforeLast("_$v")
                } else {
                    cleanName.substringBeforeLast(v)
                }
                break
            }
        }

        val familyName = family.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        return familyName to variant
    }

    private fun performImport() {
        val editorText = (targetBox.editor.editorComponent as JTextField).text
        val (moduleName, baseType, _) = parseTargetText(editorText)
        
        val itemsToImport = importedItems.filter { it.isSelected }
        if (itemsToImport.isEmpty()) return

        val generateKotlin = generateKotlinCb.isSelected
        val kotlinDestPath = kotlinPathField.text
        val packageName = packageNameField.text

        val task = object : com.intellij.openapi.progress.Task.Backgroundable(project, "Importing Fonts", true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = false
                
                try {
                    val module = if (moduleName != null) {
                        ModuleManager.getInstance(project).modules.find { 
                            it.name == moduleName
                        }
                    } else null

                    val resDirVirtual = ResourceUtils.getOrCreateResourceDir(module, project, baseType)
                        ?: throw IOException("Could not find or create resource directory for '$baseType'")
                    
                    val resDirPath = resDirVirtual.path
                    val targetFolder = java.io.File(resDirPath, "font")
                    if (!targetFolder.exists()) {
                        targetFolder.mkdirs()
                    }

                    itemsToImport.forEachIndexed { index, item ->
                        indicator.checkCanceled()
                        indicator.fraction = index.toDouble() / itemsToImport.size
                        indicator.text = "Importing ${item.name}..."

                        val targetFile = java.io.File(targetFolder, "${item.name}.${item.extension}")
                        targetFile.writeBytes(item.file.contentsToByteArray())
                    }
                    
                    VfsUtil.markDirtyAndRefresh(true, true, true, resDirVirtual)

                    if (generateKotlin && module != null && kotlinDestPath.isNotEmpty()) {
                        val fontFamilies = mutableMapOf<String, MutableList<Pair<String, String>>>()
                        itemsToImport.forEach { item ->
                            val finalFileName = "${item.name}.${item.extension}"
                            val (family, variant) = getFamilyAndVariant(item.name)
                            fontFamilies.getOrPut(family) { mutableListOf() }.add(finalFileName to variant)
                        }
                        try {
                            ResourceUtils.generateTypographyFile(
                                module = module,
                                destinationFolder = File(kotlinDestPath),
                                packageName = packageName,
                                fontFamilies = fontFamilies
                            )
                            val virtualKotlinFolder = VfsUtil.findFileByIoFile(File(kotlinDestPath), true)
                            if (virtualKotlinFolder != null) {
                                VfsUtil.markDirtyAndRefresh(true, true, true, virtualKotlinFolder)
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                } catch (e: Exception) {
                    UIUtil.invokeLaterIfNeeded {
                        Messages.showErrorDialog(project, e.message ?: MyBundle.message("error.unknown"), MyBundle.message("title.importError"))
                    }
                }
            }

            override fun onSuccess() {
                UIUtil.invokeLaterIfNeeded {
                    val licenseBanner = MyBundle.message("font.browser.license.banner")
                    Messages.showInfoMessage(project, "Successfully imported ${itemsToImport.size} local font(s).\n\n$licenseBanner", "Import Complete")
                    close(OK_EXIT_CODE)
                }
            }
        }

        com.intellij.openapi.progress.ProgressManager.getInstance().run(task)
    }
}
