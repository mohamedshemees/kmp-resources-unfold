package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.*
import com.intellij.icons.AllIcons
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
    private val mainPanel = JPanel(CardLayout())
    private lateinit var step1Panel: JPanel
    private lateinit var step2Panel: JPanel
    
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
            val countLabel = JBLabel().apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD)
            }
            add(countLabel, BorderLayout.WEST)
            
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
                            importedItems.add(ImportedDrawableItem(file))
                        }
                    }
                    refreshStep1(panel)
                }
            }
            add(importMore, BorderLayout.EAST)
        }
        
        panel.add(header, BorderLayout.NORTH)
        refreshStep1(panel)
        return panel
    }

    private fun refreshStep1(panel: JPanel) {
        val header = panel.getComponent(0) as JPanel
        val countLabel = header.getComponent(0) as JBLabel
        val count = importedItems.count { !it.doNotImport }
        countLabel.text = if (count == 1) MyBundle.message("title.step1") else MyBundle.message("title.step1_plural", count)

        val listPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty()
        }

        importedItems.forEach { item ->
            val itemPanel = createItemPanel(item) { refreshStep1(panel) }
            itemPanel.maximumSize = Dimension(Int.MAX_VALUE, itemPanel.preferredSize.height)
            listPanel.add(itemPanel)
            val sep = JSeparator()
            sep.maximumSize = Dimension(Int.MAX_VALUE, 1)
            listPanel.add(sep)
        }

        listPanel.add(Box.createVerticalGlue())

        val scrollPane = JBScrollPane(listPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        if (panel.componentCount > 1) panel.remove(1)
        panel.add(scrollPane, BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }

    private fun createItemPanel(item: ImportedDrawableItem, onUpdate: () -> Unit): JPanel {
        val PREVIEW_SIZE = 72

        val itemRowPanel = JPanel(BorderLayout(12, 0)).apply {
            border = JBUI.Borders.empty(10, 12)
            if (item.doNotImport) background = UIUtil.getPanelBackground().darker()
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

        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
        }

        val nameField = JTextField(item.name).apply {
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            font = JBUI.Fonts.label().deriveFont(Font.PLAIN, 13f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        nameField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent)  { item.name = nameField.text }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent)  { item.name = nameField.text }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) { item.name = nameField.text }
        })

        val ext  = if (item.convertSvg) "xml" else item.file.extension ?: ""
        val size = ResourceUtils.formatSize(item.file.length)
        val infoLabel = JBLabel(MyBundle.message("label.itemInfo", item.file.name, size, ext)).apply {
            foreground   = UIUtil.getLabelDisabledForeground()
            font         = JBUI.Fonts.smallFont()
            alignmentX   = Component.LEFT_ALIGNMENT
            border       = JBUI.Borders.emptyTop(4)
        }

        centerPanel.add(nameField)
        centerPanel.add(infoLabel)
        itemRowPanel.add(centerPanel, BorderLayout.CENTER)

        val doNotImportBtn = JButton(
            if (item.doNotImport) MyBundle.message("button.import_single")
            else MyBundle.message("button.doNotImport")
        ).apply {
            putClientProperty("JButton.buttonType", "link")
            addActionListener {
                item.doNotImport = !item.doNotImport
                onUpdate()
            }
        }
        val eastPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(doNotImportBtn, BorderLayout.NORTH)
        }
        itemRowPanel.add(eastPanel, BorderLayout.EAST)

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
        
        importedItems.filter { !it.doNotImport }.forEach { item ->
            val label = JBLabel("${item.name}.${item.extension}", ResourceIconProvider.getIcon(item.file, 48), SwingConstants.LEFT).apply {
                border = JBUI.Borders.empty(5, 10)
            }
            summaryPanel.add(label)
        }
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
        
        val targetDir = ResourceUtils.getTargetResourceDir(module, project) ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            try {
                importedItems.filter { !it.doNotImport }.forEach { item ->
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
                VfsUtil.markDirtyAndRefresh(true, true, true, targetDir)
            } catch (e: Exception) {
                UIUtil.invokeLaterIfNeeded {
                    Messages.showErrorDialog(project, e.message ?: MyBundle.message("error.unknown"), MyBundle.message("title.importError"))
                }
            }
        }
    }
}
