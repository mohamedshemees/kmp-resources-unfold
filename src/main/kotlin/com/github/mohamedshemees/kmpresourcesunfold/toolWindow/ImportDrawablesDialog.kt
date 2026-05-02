package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.*
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
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
import java.io.IOException
import javax.swing.*

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
    
    // Step 1 UI components for preservation
    private val step1ListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty()
    }
    private val step1ScrollPane = JBScrollPane(step1ListPanel).apply {
        border = JBUI.Borders.empty()
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val moduleBox = ComboBox<String>()
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

        // Preserve scroll position
        val scrollPos = step1ScrollPane.verticalScrollBar.value

        step1ListPanel.removeAll()

        // Group by name (base name)
        val groupedItems = importedItems.groupBy { it.name }

        groupedItems.forEach { (name, items) ->
            val sortedItems = items.sortedBy { it.density.ordinal }
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
        
        // Restore scroll position after layout
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

        // Header for the group
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
            nameField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent)  { if (nameField.hasFocus()) items.forEach { it.name = nameField.text } }
                override fun removeUpdate(e: javax.swing.event.DocumentEvent)  { if (nameField.hasFocus()) items.forEach { it.name = nameField.text } }
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) { if (nameField.hasFocus()) items.forEach { it.name = nameField.text } }
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

        // Sub-items (densities)
        val itemsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyLeft(20)
        }

        items.forEach { item ->
            itemsPanel.add(createItemRow(item, isSummary, onUpdate))
        }
        groupPanel.add(itemsPanel, BorderLayout.CENTER)

        return groupPanel
    }

    private fun createItemRow(item: ImportedDrawableItem, isSummary: Boolean, onUpdate: () -> Unit): JPanel {
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
        }

        val densityLabel = JBLabel(item.density.displayName).apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD)
        }
        infoPanel.add(densityLabel)

        if (isSummary) {
            val module = moduleBox.selectedItem as? String ?: ""
            val m = ModuleManager.getInstance(project).modules.find { it.name == module }
            val resDirName = ResourceUtils.getComposeResourcesDir(m ?: return JPanel(), project)?.name ?: "composeResources"
            val densityDir = "drawable${item.density.directoryQualifier}"
            val targetPath = "$resDirName/$densityDir/${item.name}.${item.extension}"
            
            val pathLabel = JBLabel(targetPath).apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getLabelDisabledForeground()
            }
            infoPanel.add(pathLabel)
        } else {
            val ext  = if (item.convertSvg) "xml" else item.file.extension ?: ""
            val size = ResourceUtils.formatSize(item.file.length)
            val infoLabel = JBLabel(MyBundle.message("label.itemInfo", item.file.name, size, ext)).apply {
                foreground   = UIUtil.getLabelDisabledForeground()
                font         = JBUI.Fonts.smallFont()
            }
            infoPanel.add(infoLabel)

            if (item.file.extension?.lowercase() == ResourceExtension.SVG.extension) {
                val convertCheckbox = JCheckBox(MyBundle.message("prompt.convertSvg"), item.convertSvg).apply {
                    isOpaque = false
                    font = JBUI.Fonts.smallFont()
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

        // Force fixed height to match Step 1 and prevent stretching
        itemRowPanel.maximumSize = Dimension(Int.MAX_VALUE, itemRowPanel.preferredSize.height)

        return itemRowPanel
    }

    private fun createStep2Panel(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(20)
        }
        
        val availableModules = ModuleManager.getInstance(project).modules.filter { module ->
            val contentRoots = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).contentRoots
            contentRoots.any { it.path.contains("src/commonMain") }
        }.map { it.name }.sorted()

        moduleBox.removeAllItems()
        availableModules.forEach { moduleBox.addItem(it) }

        val form = panel {
            row(MyBundle.message("label.sourceSet")) {
                cell(moduleBox).align(com.intellij.ui.dsl.builder.AlignX.FILL)
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
        val selectedModuleName = moduleBox.selectedItem as? String ?: return
        val module = ModuleManager.getInstance(project).modules.find { it.name == selectedModuleName } ?: return
        
        val resDir = ResourceUtils.getComposeResourcesDir(module, project) ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            try {
                importedItems.filter { !it.doNotImport }.forEach { item ->
                    val densityDirName = "drawable${item.density.directoryQualifier}"
                    val targetDir = VfsUtil.createDirectoryIfMissing(resDir, densityDirName) ?: return@forEach

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
                VfsUtil.markDirtyAndRefresh(true, true, true, resDir)
            } catch (e: Exception) {
                UIUtil.invokeLaterIfNeeded {
                    Messages.showErrorDialog(project, e.message ?: MyBundle.message("error.unknown"), MyBundle.message("title.importError"))
                }
            }
        }
    }
}
