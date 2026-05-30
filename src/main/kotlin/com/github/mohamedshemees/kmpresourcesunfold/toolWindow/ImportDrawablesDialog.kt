package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.*
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.IOException
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ImportDrawablesDialog(private val project: Project, initialFiles: List<VirtualFile>) : DialogWrapper(project) {

    private enum class ImportStep(val id: String) {
        SELECT_RESOURCES("ImportingDrawablesStep"),
        CHOOSE_TARGET("ChooseImportLocationStep")
    }

    private val importedItems = initialFiles.map { ImportedDrawableItem(it) }.toMutableList()
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
    private data class TargetOption(
        val module: Module?, 
        val targetType: String,
        val file: VirtualFile?,
        val isCustom: Boolean = false
    ) {
        override fun toString(): String {
            val cleanName = module?.name?.let { with(ResourceUtils) { it.cleanModuleName() } }
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
        title = MyBundle.message("title.importDrawables")
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
        
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10, 10, 5, 10)
            
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
            val countLabel = JBLabel().apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD)
            }
            leftPanel.add(countLabel)
            
            val prefixCheckbox = JCheckBox(MyBundle.message("prompt.applyPrefix"), applyPrefix).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(20)
                addActionListener {
                    applyPrefix = isSelected
                    importedItems.forEach { it.applyPrefix(applyPrefix) }
                    refreshStep1(panel)
                }
            }
            leftPanel.add(prefixCheckbox)
            add(leftPanel, BorderLayout.WEST)
            
            val importMore = JButton(MyBundle.message("button.importMore")).apply {
                putClientProperty("JButton.buttonType", "link")
                addActionListener {
                    val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
                    descriptor.withFileFilter { file ->
                        val ext = file.extension?.lowercase()
                        ext == "png" || ext == "jpg" || ext == "jpeg" || ext == "webp" || ext == "svg" || ext == "xml"
                    }
                    descriptor.title = MyBundle.message("title.selectImages")

                    val files = FileChooser.chooseFiles(descriptor, project, null)
                    files.forEach { file ->
                        if (importedItems.none { it.file == file }) {
                            importedItems.add(ImportedDrawableItem(file).apply { applyPrefix(applyPrefix) })
                        }
                    }
                    refreshStep1(panel)
                }
            }
            add(importMore, BorderLayout.EAST)
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
                    if (importedItems.none { it.file == file }) {
                        importedItems.add(ImportedDrawableItem(file).apply { applyPrefix(applyPrefix) })
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
        val leftPanel = header.getComponent(0) as JPanel
        val countLabel = leftPanel.getComponent(0) as JBLabel
        val count = importedItems.size
        countLabel.text = if (count == 1) MyBundle.message("title.step1") else MyBundle.message("title.step1_plural", count)

        val scrollPos = step1ScrollPane.verticalScrollBar.value

        step1ListPanel.removeAll()

        val groupedItems = importedItems.groupBy { it.name }

        groupedItems.forEach { (name, items) ->
            val sortedItems = items.sortedWith(compareBy({ it.detectedDensity.ordinal }, { it.file.name }))
            val itemPanel = createGroupPanel(name, sortedItems, isSummary = false) { refreshStep1(panel) }
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
        items: List<ImportedDrawableItem>, 
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
                        val currentPrefix = if (it.name.startsWith("ic_") || it.name.startsWith("img_")) it.name.substringBefore("_") + "_" else ""
                        it.name = if (applyPrefix && !text.startsWith(currentPrefix)) "$currentPrefix$text" else text
                    }
                    onUpdate()
                }
            })
            headerPanel.add(nameField, BorderLayout.CENTER)

            val removeAction = object : AnAction(AllIcons.Actions.Close) {
                override fun actionPerformed(e: AnActionEvent) {
                    importedItems.removeAll(items)
                    onUpdate()
                }
            }
            val removeBtn = ActionButton(removeAction, removeAction.templatePresentation, "KmpResourcesUnfold", Dimension(22, 22))
            headerPanel.add(removeBtn, BorderLayout.EAST)
        }
        
        groupPanel.add(headerPanel, BorderLayout.NORTH)

        val itemsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyLeft(20)
        }

        items.forEach { item ->
            val row = createItemRow(item, items, isSummary, onUpdate)
            itemsPanel.add(row)
        }
        groupPanel.add(itemsPanel, BorderLayout.CENTER)

        return groupPanel
    }

    private fun createItemRow(
        item: ImportedDrawableItem, 
        groupItems: List<ImportedDrawableItem>,
        isSummary: Boolean, 
        onUpdate: () -> Unit
    ): JPanel {
        val PREVIEW_SIZE = 48

        val itemRowPanel = JPanel(BorderLayout(12, 0)).apply {
            border = JBUI.Borders.empty(5, 12)
            isOpaque = false
        }

        val previewLabel = JBLabel().apply {
            preferredSize = Dimension(PREVIEW_SIZE, PREVIEW_SIZE)
            minimumSize  = Dimension(PREVIEW_SIZE, PREVIEW_SIZE)
            maximumSize  = Dimension(PREVIEW_SIZE, PREVIEW_SIZE)
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment   = SwingConstants.CENTER
            border = JBUI.Borders.customLine(JBColor.border())
            icon = ResourceIconProvider.getIcon(item.file, PREVIEW_SIZE)
        }
        itemRowPanel.add(previewLabel, BorderLayout.WEST)

        val infoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        if (isSummary) {
            val editorText = (targetBox.editor.editorComponent as JTextField).text
            val (_, baseType, subPath) = parseTargetText(editorText)
            
            val selectedTarget = targetBox.selectedItem as? TargetOption
            val resDirName = selectedTarget?.file?.name ?: when(baseType) {
                "Android" -> "res"
                "Compose" -> "composeResources"
                "Assets" -> "assets"
                else -> baseType.ifEmpty { "composeResources" }
            }
            
            val folderBase = if (subPath != null) subPath.replace(".", "/") else "drawable"
            val densityDir = when (baseType) {
                "Android", "Compose" -> "${folderBase}${item.density.directoryQualifier}"
                else -> folderBase
            }
            
            val targetPath = if (densityDir.isNotEmpty()) "$resDirName/$densityDir/${item.name}.${item.extension}" else "$resDirName/${item.name}.${item.extension}"
            
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
            val densityBox = ComboBox(Density.ALL_DENSITIES.toTypedArray()).apply {
                selectedItem = if (item.density == Density.DEFAULT) Density.MDPI else item.density
                font = JBUI.Fonts.label().deriveFont(Font.BOLD, 12f)
                alignmentX = Component.LEFT_ALIGNMENT
                
                var isInternalChange = false
                addActionListener {
                    if (isInternalChange) return@addActionListener
                    val newDensity = selectedItem as Density
                    val oldDensity = item.density
                    
                    if (newDensity != oldDensity) {
                        val conflictItem = groupItems.find { it != item && it.density == newDensity }
                        if (conflictItem != null) {
                            conflictItem.density = oldDensity
                        }
                        item.density = newDensity
                        onUpdate()
                    }
                }
            }
            infoPanel.add(densityBox)

            val ext  = if (item.convertSvg) "xml" else item.file.extension ?: ""
            val size = ResourceUtils.formatSize(item.file.length)
            val infoLabel = JBLabel(MyBundle.message("label.itemInfo", "", size, ext)).apply {
                foreground   = UIUtil.getLabelDisabledForeground()
                font         = JBUI.Fonts.smallFont()
                alignmentX   = Component.LEFT_ALIGNMENT
            }
            infoPanel.add(infoLabel)

            if (item.file.extension?.lowercase() == ResourceExtension.SVG.extension) {
                val convertCheckbox = JCheckBox(MyBundle.message("prompt.convertSvg"), item.convertSvg).apply {
                    isOpaque = false
                    font = JBUI.Fonts.smallFont()
                    alignmentX = Component.LEFT_ALIGNMENT
                    addActionListener {
                        item.convertSvg = isSelected
                        onUpdate()
                    }
                }
                infoPanel.add(convertCheckbox)
            }
        }

        itemRowPanel.add(infoPanel, BorderLayout.CENTER)

        if (!isSummary) {
            val individualRemoveAction = object : AnAction(AllIcons.Actions.Close) {
                override fun actionPerformed(e: AnActionEvent) {
                    importedItems.remove(item)
                    onUpdate()
                }
            }
            val individualRemoveBtn = ActionButton(individualRemoveAction, individualRemoveAction.templatePresentation, "KmpResourcesUnfold", Dimension(18, 18))
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
                val hasResources = ResourceUtils.getComposeResourcesDir(module, project) != null || 
                                   ResourceUtils.getAndroidResourcesDir(module, project) != null
                !name.contains("ios") && !name.contains("test") && hasResources
            }.sortedBy { it.name }

            modules.forEach { module ->
                val existingTargets = ResourceUtils.getAllResourceDirs(module, project)
                
                optionsList.add(TargetOption(module, "Compose", existingTargets.find { it.first == "Compose" }?.second))
                optionsList.add(TargetOption(module, "Android", existingTargets.find { it.first == "Android" }?.second))
                
                val existingAssets = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).contentRoots
                    .firstNotNullOfOrNull { com.intellij.openapi.vfs.VfsUtil.findRelativeFile(it, "assets") }
                optionsList.add(TargetOption(module, "Assets", existingAssets))
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

        targetBox.addActionListener {
            if (!isFiltering) {
                val selected = targetBox.selectedItem as? TargetOption
                if (selected != null && selected != lastSelectedTarget) {
                    lastSelectedTarget = selected
                    refreshStep2()
                }
            }
        }

        val form = panel {
            row(MyBundle.message("label.targetPackage")) {
                cell(targetBox).align(com.intellij.ui.dsl.builder.AlignX.FILL)
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
        
        val groupedItems = importedItems.filter { !it.doNotImport }.groupBy { it.name }

        groupedItems.forEach { (name, items) ->
            val sortedItems = items.sortedBy { it.density.ordinal }
            val groupPanel = createGroupPanel(name, sortedItems, isSummary = true) {}
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

    private fun performImport() {
        val editorText = (targetBox.editor.editorComponent as JTextField).text
        val (moduleName, baseType, subPath) = parseTargetText(editorText)
        
        val itemsToImport = importedItems.filter { !it.doNotImport }
        if (itemsToImport.isEmpty()) return

        val task = object : com.intellij.openapi.progress.Task.Backgroundable(project, MyBundle.message("title.importDrawables"), true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = false
                
                try {
                    val module = if (moduleName != null) {
                        ModuleManager.getInstance(project).modules.find { 
                            with(ResourceUtils) { it.name.cleanModuleName() } == moduleName || it.name == moduleName
                        }
                    } else null

                    val resDir = ResourceUtils.getOrCreateResourceDir(module, project, baseType)
                        ?: throw IOException("Could not find or create resource directory for '$baseType'")

                    itemsToImport.forEachIndexed { index, item ->
                        indicator.checkCanceled()
                        indicator.fraction = index.toDouble() / itemsToImport.size
                        indicator.text = "Importing ${item.name}..."

                        WriteCommandAction.runWriteCommandAction(project) {
                            val folderBase = if (subPath != null) subPath.replace(".", "/") else "drawable"
                            val densityDirName = when (baseType) {
                                "Android", "Compose" -> "${folderBase}${item.density.directoryQualifier}"
                                else -> if (subPath != null) subPath.replace(".", "/") else ""
                            }
                            
                            val targetDir = if (densityDirName.isNotEmpty()) {
                                VfsUtil.createDirectoryIfMissing(resDir, densityDirName) ?: return@runWriteCommandAction
                            } else {
                                resDir
                            }

                            if (item.convertSvg) {
                                val xmlContent = SvgToXmlConverter.convertToXml(item.file.inputStream)
                                val newFileName = "${item.name}.xml"
                                val existingFile = targetDir.findChild(newFileName)
                                val newFile = existingFile ?: targetDir.createChildData(this, newFileName)
                                VfsUtil.saveText(newFile, xmlContent)
                            } else {
                                val newFileName = "${item.name}.${item.file.extension}"
                                val existingFile = targetDir.findChild(newFileName)
                                if (existingFile != null) {
                                    existingFile.setBinaryContent(item.file.contentsToByteArray())
                                } else {
                                    val copied = VfsUtil.copy(this, item.file, targetDir)
                                    copied.rename(this, newFileName)
                                }
                            }
                        }
                    }
                    
                    VfsUtil.markDirtyAndRefresh(true, true, true, resDir)
                } catch (e: Exception) {
                    UIUtil.invokeLaterIfNeeded {
                        Messages.showErrorDialog(project, e.message ?: MyBundle.message("error.unknown"), MyBundle.message("title.importError"))
                    }
                }
            }

            override fun onSuccess() {
                UIUtil.invokeLaterIfNeeded {
                    close(OK_EXIT_CODE)
                }
            }
        }

        com.intellij.openapi.progress.ProgressManager.getInstance().run(task)
    }
}
