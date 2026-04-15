package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.*
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class MyToolWindowFactory : ToolWindowFactory {

    private var allFiles = listOf<VirtualFile>()
    private var currentTypeFilter = ResourceType.ALL
    private var clickTimer: Timer? = null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mainPanel = SimpleToolWindowPanel(true, true)
        allFiles = KmpResourceScanner.findComposeDrawables(project)

        val availableModules = allFiles.mapNotNull { file ->
            ModuleUtilCore.findModuleForFile(file, project)?.name
        }.distinct().sorted()

        val topPanel = JPanel(BorderLayout())
        val filterPanel = JPanel(BorderLayout(0, 5))
        filterPanel.border = BorderFactory.createEmptyBorder(10, 0, 5, 0)

        val chipPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        val chips = mutableListOf<FilterChip>()
        lateinit var updateList: () -> Unit

        val onChipClicked = { clickedChip: FilterChip ->
            chips.forEach { it.setSelectedState(it == clickedChip) }
            currentTypeFilter = ResourceType.fromFilterName(clickedChip.filterName)
            updateList()
        }

        chips.add(FilterChip(MyBundle.message("filter.all"), true, 0, onChipClicked))
        chips.add(FilterChip(MyBundle.message("filter.vectors"), false, 0, onChipClicked))
        chips.add(FilterChip(MyBundle.message("filter.images"), false, 0, onChipClicked))
        chips.forEach { chipPanel.add(it) }

        val modulePanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        modulePanel.add(JLabel(MyBundle.message("label.module")))
        val moduleBox = ComboBox<String>()
        moduleBox.addItem(MyBundle.message("item.allModules"))
        availableModules.forEach { moduleBox.addItem(it) }
        modulePanel.add(moduleBox)

        filterPanel.add(chipPanel, BorderLayout.NORTH)
        filterPanel.add(modulePanel, BorderLayout.CENTER)

        val searchField = SearchTextField()
        val searchPanel = JPanel(BorderLayout())
        searchPanel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        searchPanel.add(searchField, BorderLayout.CENTER)

        topPanel.add(filterPanel, BorderLayout.NORTH)
        topPanel.add(searchPanel, BorderLayout.SOUTH)

        val resourceModel = DefaultListModel<VirtualFile>()
        val resourceList = JBList(resourceModel).apply {
            selectionBackground = background
            isFocusable = false
        }

        resourceList.cellRenderer = ResourceListCellRenderer()

        resourceList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val selectedFile = resourceList.selectedValue ?: return
                if (e.clickCount == 2) {
                    clickTimer?.stop()
                    FileEditorManager.getInstance(project).openFile(selectedFile, true)
                } else if (e.clickCount == 1) {
                    clickTimer?.stop()
                    clickTimer = Timer(250) {
                        val nameWithoutExt = selectedFile.nameWithoutExtension
                        val composeCode = ResourceConstants.PAINTER_RESOURCE_TEMPLATE.format(nameWithoutExt)
                        CopyPasteManager.getInstance().setContents(StringSelection(composeCode))
                        JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(
                            MyBundle.message("message.copied", composeCode),
                            null,
                            JBColor.namedColor("ToolTip.background", JBColor.background()),
                            null
                        )
                            .setFadeoutTime(2000).createBalloon().show(RelativePoint(e), Balloon.Position.above)
                    }.apply { isRepeats = false; start() }
                }
            }
        })


        updateList = {
            resourceModel.clear()
            val lowerSearch = searchField.text.lowercase()
            val allModulesStr = MyBundle.message("item.allModules")
            val selectedModule = moduleBox.selectedItem as String
            allFiles.forEach { file ->
                val ext = ResourceExtension.fromExtension(file.extension)
                val fileModule = ModuleUtilCore.findModuleForFile(file, project)?.name
                val matchesType = when (currentTypeFilter) {
                    ResourceType.VECTORS -> ResourceExtension.vectorExtensions.contains(ext)
                    ResourceType.IMAGES -> ResourceExtension.imageExtensions.contains(ext)
                    else -> true
                }
                if (file.name.lowercase().contains(lowerSearch) && 
                    (selectedModule == allModulesStr || fileModule == selectedModule) && 
                    matchesType) {
                    resourceModel.addElement(file)
                }
            }
            chips.forEach { it.updateCount(resourceModel.size) }
        }

        updateList()
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateList()
            override fun removeUpdate(e: DocumentEvent) = updateList()
            override fun changedUpdate(e: DocumentEvent) = updateList()
        })
        moduleBox.addActionListener { updateList() }

        val contentPanel = JPanel(BorderLayout())
        contentPanel.add(topPanel, BorderLayout.NORTH)
        contentPanel.add(JBScrollPane(resourceList), BorderLayout.CENTER)
        mainPanel.setContent(contentPanel)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(mainPanel, "", false))
    }
}
