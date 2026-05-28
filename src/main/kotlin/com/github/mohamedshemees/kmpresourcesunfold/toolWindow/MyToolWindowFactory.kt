package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.*
import com.github.mohamedshemees.kmpresourcesunfold.bridge.FigmaBridgeService
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

data class ResourceParent(val name: String, val icon: Icon?, val representativeFile: VirtualFile?)
data class ResourceChild(val file: VirtualFile, val density: String)

data class ModuleOption(val name: String, val displayName: String) {
    override fun toString() = displayName
}

class MyToolWindowFactory : ToolWindowFactory, DumbAware {

    private var allFiles = listOf<VirtualFile>()
    private var allStringResources = listOf<StringResource>()
    private var currentTypeFilter = ResourceType.ALL
    private var clickTimer: Timer? = null

    private fun getDensity(file: VirtualFile): String {
        val parent = file.parent ?: return "Default"
        val name = parent.name
        return when {
            name == "drawable" -> "Default"
            name.startsWith("drawable-") -> name.substringAfter("drawable-").uppercase()
            else -> "Default"
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mainPanel = SimpleToolWindowPanel(true, true)
        lateinit var updateList: () -> Unit
        lateinit var updateModuleBox: () -> Unit

        var availableModules = listOf<String>()

        val refreshData = {
            ResourceIconProvider.clearCache()
            allFiles = KmpResourceScanner.findComposeDrawables(project)
            allStringResources = StringResourceProcessor.findStringResources(project, allFiles)
            availableModules = allFiles.mapNotNull { file ->
                ModuleUtilCore.findModuleForFile(file, project)?.name
            }.distinct().sorted()
        }

        refreshData()

        val actionGroup = DefaultActionGroup()
        val addAction = object : AnAction(MyBundle.message("action.import.title"), MyBundle.message("action.import.description"), com.intellij.icons.AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) {
                val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
                descriptor.title = MyBundle.message("title.selectImages")
                descriptor.withExtensionFilter(MyBundle.message("filter.images"), *ResourceExtension.allExtensions.toTypedArray() )
                
                val files = FileChooser.chooseFiles(descriptor, project, null)
                if (files.isNotEmpty()) {
                    ImportDrawablesDialog(project, files.toList()).show()
                }
            }
        }

        actionGroup.add(addAction)

        val refreshAction = object : AnAction(
            MyBundle.message("action.refresh.title"),
            MyBundle.message("action.refresh.description"),
            com.intellij.icons.AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshData()
                updateModuleBox()
                updateList()
            }
        }
        actionGroup.add(refreshAction)

        val toolbar = ActionManager.getInstance().createActionToolbar("KmpResourcesUnfoldToolbar", actionGroup, true)
        toolbar.targetComponent = mainPanel
        mainPanel.toolbar = toolbar.component

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

        project.messageBus.connect(toolWindow.disposable)
            .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    UIUtil.invokeLaterIfNeeded {
                        refreshData()
                        updateModuleBox()
                        updateList()
                    }
                }
            })

        val modulePanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        modulePanel.add(JLabel(MyBundle.message("label.module")))
        val moduleBox = ComboBox<ModuleOption>()
        moduleBox.isEditable = true
        val editor = moduleBox.editor.editorComponent as JTextField
        var isFiltering = false
        var lastSelectedModule: ModuleOption? = null
        val allModulesOption = ModuleOption(MyBundle.message("item.allModules"), MyBundle.message("item.allModules"))
        val allModuleOptions = mutableListOf<ModuleOption>()
        
        updateModuleBox = {
            val selectedBefore = moduleBox.selectedItem as? ModuleOption
            allModuleOptions.clear()
            allModuleOptions.add(allModulesOption)
            availableModules.forEach {
                val cleanName = with(ResourceUtils) { it.cleanModuleName() }
                allModuleOptions.add(ModuleOption(it, cleanName))
            }
            
            isFiltering = true
            moduleBox.removeAllItems()
            allModuleOptions.forEach { moduleBox.addItem(it) }
            
            if (selectedBefore != null) {
                val found = allModuleOptions.find { it.name == selectedBefore.name }
                if (found != null) {
                    moduleBox.selectedItem = found
                    lastSelectedModule = found
                } else {
                    moduleBox.selectedIndex = 0
                    lastSelectedModule = allModulesOption
                }
            } else {
                moduleBox.selectedIndex = 0
                lastSelectedModule = allModulesOption
            }
            isFiltering = false
        }

        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filter()
            override fun removeUpdate(e: DocumentEvent) = filter()
            override fun changedUpdate(e: DocumentEvent) = filter()
            
            fun filter() {
                if (isFiltering) return
                SwingUtilities.invokeLater {
                    if (isFiltering) return@invokeLater
                    
                    val text = editor.text
                    val currentSelected = moduleBox.selectedItem as? ModuleOption
                    val isExactMatch = currentSelected != null && currentSelected.displayName == text
                    
                    if (!isExactMatch) {
                        isFiltering = true
                        val caret = editor.caretPosition
                        moduleBox.removeAllItems()
                        val matches = allModuleOptions.filter { it.displayName.lowercase().contains(text.lowercase()) }
                        matches.forEach { moduleBox.addItem(it) }
                        
                        editor.text = text
                        editor.caretPosition = caret
                        
                        if (matches.isNotEmpty() && !moduleBox.isPopupVisible && editor.hasFocus()) {
                            moduleBox.showPopup()
                        }
                        isFiltering = false
                    }
                    updateList()
                }
            }
        })
        
        moduleBox.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) {}
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {
                SwingUtilities.invokeLater {
                    isFiltering = true
                    val currentSelected = moduleBox.selectedItem
                    moduleBox.removeAllItems()
                    allModuleOptions.forEach { moduleBox.addItem(it) }
                    moduleBox.selectedItem = currentSelected
                    isFiltering = false
                }
            }
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {}
        })

        editor.addActionListener {
            moduleBox.hidePopup()
            val text = editor.text
            val match = allModuleOptions.find { it.displayName == text } ?: if (moduleBox.itemCount > 0) moduleBox.getItemAt(0) else null
            if (match != null) {
                isFiltering = true
                moduleBox.selectedItem = match
                editor.text = match.displayName
                isFiltering = false
                if (match != lastSelectedModule) {
                    lastSelectedModule = match
                    updateList()
                }
            }
        }

        updateModuleBox()

        moduleBox.addActionListener { 
            if (!isFiltering) {
                val selected = moduleBox.selectedItem as? ModuleOption
                if (selected != null && selected != lastSelectedModule) {
                    lastSelectedModule = selected
                    updateList() 
                }
            }
        }

        modulePanel.add(moduleBox)

        filterPanel.add(chipPanel, BorderLayout.NORTH)
        filterPanel.add(modulePanel, BorderLayout.CENTER)

        val searchField = SearchTextField()
        val searchPanel = JPanel(BorderLayout())
        searchPanel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        searchPanel.add(searchField, BorderLayout.CENTER)
        
        val portLabel = JLabel("Figma Bridge running on port: ${FigmaBridgeService.getInstance(project).activePort ?: "Starting..."}")
        val portPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        portPanel.border = BorderFactory.createEmptyBorder(0, 5, 5, 5)
        portPanel.add(portLabel)

        val southPanel = JPanel(BorderLayout())
        southPanel.add(searchPanel, BorderLayout.NORTH)
        southPanel.add(portPanel, BorderLayout.SOUTH)

        topPanel.add(filterPanel, BorderLayout.NORTH)
        topPanel.add(southPanel, BorderLayout.SOUTH)

        val resourceModel = DefaultListModel<Any>()
        val resourceList = JBList(resourceModel).apply {
            selectionBackground = background
            isFocusable = false
        }

        resourceList.cellRenderer = ResourceListCellRenderer(project)

        resourceList.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val files = support.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<java.io.File>
                val virtualFiles = files.mapNotNull { VirtualFileManager.getInstance().findFileByNioPath(it.toPath()) }
                if (virtualFiles.isNotEmpty()) {
                    ImportDrawablesDialog(project, virtualFiles).show()
                }
                return true
            }
        }

        resourceList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val selected = resourceList.selectedValue ?: return
                
                if (e.clickCount == 2) {
                    clickTimer?.stop()
                    val fileToOpen = when (selected) {
                        is ResourceChild -> selected.file
                        is ResourceParent -> selected.representativeFile
                        is StringResource -> selected.file
                        is VirtualFile -> selected
                        else -> null
                    }
                    fileToOpen?.let { FileEditorManager.getInstance(project).openFile(it, true) }
                } else if (e.clickCount == 1) {
                    clickTimer?.stop()
                    clickTimer = Timer(250) {
                        val code = when (selected) {
                            is ResourceParent -> selected.name
                            is ResourceChild -> selected.file.nameWithoutExtension
                            is StringResource -> selected.key
                            is VirtualFile -> selected.nameWithoutExtension
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
            val selectedModule = (moduleBox.selectedItem as? ModuleOption)?.name ?: ""

            val filteredFiles = allFiles.filter { file ->
                val ext = ResourceExtension.fromExtension(file.extension)
                val moduleName = ModuleUtilCore.findModuleForFile(file, project)?.name
                val matchesModule = selectedModule == allModulesStr || moduleName == selectedModule
                val matchesType = when (currentTypeFilter) {
                    ResourceType.VECTORS -> ResourceExtension.vectorExtensions.contains(ext)
                    ResourceType.IMAGES -> ResourceExtension.imageExtensions.contains(ext)
                    ResourceType.STRINGS -> false
                    else -> ext != ResourceExtension.XML || !file.path.contains("values")
                }
                val matchesSearch = file.name.lowercase().contains(lowerSearch)
                matchesModule && matchesSearch && matchesType
            }

            val filteredStrings = if (currentTypeFilter == ResourceType.ALL || currentTypeFilter == ResourceType.STRINGS) {
                allStringResources.filter { resource ->
                    val moduleName = ModuleUtilCore.findModuleForFile(resource.file, project)?.name
                    val matchesModule = selectedModule == allModulesStr || moduleName == selectedModule
                    val matchesSearch = resource.key.lowercase().contains(lowerSearch)
                    matchesModule && matchesSearch
                }
            } else emptyList()

            val filteredStringsFiles = if (currentTypeFilter == ResourceType.ALL || currentTypeFilter == ResourceType.STRINGS) {
                allFiles.filter { file ->
                    val moduleName = ModuleUtilCore.findModuleForFile(file, project)?.name
                    val matchesModule = selectedModule == allModulesStr || moduleName == selectedModule
                    val matchesSearch = file.name.lowercase().contains(lowerSearch)
                    file.name == ResourceConstants.STRINGS_FILE && matchesModule && matchesSearch
                }
            } else emptyList()

            if (filteredFiles.isNotEmpty()) {
                val densityOrder = listOf("Default", "LDPI", "MDPI", "HDPI", "XHDPI", "XXHDPI", "XXXHDPI")
                val groupedByName = filteredFiles.groupBy { it.nameWithoutExtension }
                
                groupedByName.keys.sorted().forEach { name ->
                    val files = groupedByName[name] ?: return@forEach
                    val sortedFiles = files.sortedWith(compareBy({ getDensity(it).let { d -> densityOrder.indexOf(d).takeIf { i -> i >= 0 } ?: 100 } }, { it.name }))
                    
                    val representative = sortedFiles.find { getDensity(it) == "Default" } ?: sortedFiles.first()
                    
                    resourceModel.addElement(ResourceParent(name, ResourceIconProvider.getIcon(representative), representative))
                    
                    sortedFiles.forEach { file ->
                        resourceModel.addElement(ResourceChild(file, getDensity(file)))
                    }
                }
            }

            if (filteredStrings.isNotEmpty() || filteredStringsFiles.isNotEmpty()) {
                resourceModel.addElement(ResourceParent("Strings", UIManager.getIcon("FileView.fileIcon"), filteredStringsFiles.firstOrNull()))
                filteredStrings.sortedBy { it.key }.forEach { resourceModel.addElement(it) }
                filteredStringsFiles.sortedBy { it.name }.forEach { resourceModel.addElement(it) }
            }

            val totalResourcesCount = filteredFiles.size + filteredStrings.size + filteredStringsFiles.size
            chips.forEach { it.updateCount(totalResourcesCount) }
        }


        updateList()
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateList()
            override fun removeUpdate(e: DocumentEvent) = updateList()
            override fun changedUpdate(e: DocumentEvent) = updateList()
        })

        val contentPanel = JPanel(BorderLayout())
        contentPanel.add(topPanel, BorderLayout.NORTH)
        contentPanel.add(JBScrollPane(resourceList), BorderLayout.CENTER)
        mainPanel.setContent(contentPanel)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(mainPanel, "", false))
    }
}
