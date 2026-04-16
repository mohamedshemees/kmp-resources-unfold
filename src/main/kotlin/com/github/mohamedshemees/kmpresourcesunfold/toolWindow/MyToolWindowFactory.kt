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
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
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
    private var allStringResources = listOf<StringResource>()
    private var currentTypeFilter = ResourceType.ALL
    private var clickTimer: Timer? = null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mainPanel = SimpleToolWindowPanel(true, true)
        lateinit var updateList: () -> Unit

        val refreshData = {
            allFiles = KmpResourceScanner.findComposeDrawables(project)
            allStringResources = StringResourceProcessor.findStringResources(project, allFiles)
        }

        refreshData()

        val availableModules = allFiles.mapNotNull { file ->
            ModuleUtilCore.findModuleForFile(file, project)?.name
        }.distinct().sorted()

        val topPanel = JPanel(BorderLayout())
        val filterPanel = JPanel(BorderLayout(0, 5))
        filterPanel.border = BorderFactory.createEmptyBorder(10, 0, 5, 0)

        val chipPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        val chips = mutableListOf<FilterChip>()

        val onChipClicked = { clickedChip: FilterChip ->
            chips.forEach { it.setSelectedState(it == clickedChip) }
            currentTypeFilter = ResourceType.fromFilterName(clickedChip.filterName)
            updateList()
        }

        chips.add(FilterChip(MyBundle.message("filter.all"), true, 0, onChipClicked))
        chips.add(FilterChip(MyBundle.message("filter.vectors"), false, 0, onChipClicked))
        chips.add(FilterChip(MyBundle.message("filter.images"), false, 0, onChipClicked))
        chips.add(FilterChip(MyBundle.message("filter.strings"), false, 0, onChipClicked))
        chips.forEach { chipPanel.add(it) }

        project.messageBus.connect(toolWindow.disposable).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                UIUtil.invokeLaterIfNeeded {
                    refreshData()
                    updateList()
                }
            }
        })

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

        val resourceModel = DefaultListModel<Any>()
        val resourceList = JBList(resourceModel).apply {
            selectionBackground = background
            isFocusable = false
        }

        resourceList.cellRenderer = ResourceListCellRenderer()

        resourceList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val selected = resourceList.selectedValue ?: return
                if (e.clickCount == 2) {
                    clickTimer?.stop()
                    val fileToOpen = when (selected) {
                        is VirtualFile -> selected
                        is StringResource -> selected.file
                        else -> null
                    }
                    fileToOpen?.let { FileEditorManager.getInstance(project).openFile(it, true) }
                } else if (e.clickCount == 1) {
                    clickTimer?.stop()
                    clickTimer = Timer(250) {
                        val code = when (selected) {
                            is VirtualFile -> selected.nameWithoutExtension
                            is StringResource -> selected.key
                            else -> ""
                        }
                        if (code.isNotEmpty()) {
                            CopyPasteManager.getInstance().setContents(StringSelection(code))
                            JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(
                                MyBundle.message("message.copied", code),
                                null,
                                JBColor.namedColor("ToolTip.background", JBColor.background()),
                                null
                            )
                                .setFadeoutTime(2000).createBalloon().show(RelativePoint(e), Balloon.Position.above)
                        }
                    }.apply { isRepeats = false; start() }
                }
            }
        })

        updateList = {
            resourceModel.clear()
            val lowerSearch = searchField.text.lowercase()
            val allModulesStr = MyBundle.message("item.allModules")
            val selectedModule = moduleBox.selectedItem as String

            when (currentTypeFilter) {
                ResourceType.STRINGS -> {
                    allStringResources.filter { resource ->
                        val moduleName = ModuleUtilCore.findModuleForFile(resource.file, project)?.name
                        val matchesModule = selectedModule == allModulesStr || moduleName == selectedModule
                        val matchesSearch = resource.key.lowercase().contains(lowerSearch)
                        matchesModule && matchesSearch
                    }.forEach { resourceModel.addElement(it) }
                }
                else -> {
                    allFiles.forEach { file ->
                        val ext = ResourceExtension.fromExtension(file.extension)
                        val moduleName = ModuleUtilCore.findModuleForFile(file, project)?.name
                        val matchesModule = selectedModule == allModulesStr || moduleName == selectedModule
                        val matchesType = when (currentTypeFilter) {
                            ResourceType.VECTORS -> ResourceExtension.vectorExtensions.contains(ext)
                            ResourceType.IMAGES -> ResourceExtension.imageExtensions.contains(ext)
                            else -> ext != ResourceExtension.XML || !file.path.contains("values")
                        }
                        val matchesSearch = file.name.lowercase().contains(lowerSearch)
                        
                        if (matchesModule && matchesSearch && matchesType) {
                            resourceModel.addElement(file)
                        }
                    }
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
