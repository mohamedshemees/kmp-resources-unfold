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
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
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
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

data class ResourceParent(
    val name: String, 
    val icon: Icon?, 
    val representativeFile: VirtualFile?,
    val subtitle: String = "",
    val isUnused: Boolean = false
)
data class ResourceChild(val file: VirtualFile, val density: String, val moduleName: String)
data class ResourceFileItem(val file: VirtualFile, val moduleName: String)

data class ModuleOption(val name: String, val displayName: String) {
    override fun toString() = displayName
}

class MyToolWindowFactory : ToolWindowFactory, DumbAware {

    private var allFiles = listOf<VirtualFile>()
    private var allStringResources = listOf<StringResource>()
    private var currentTypeFilter = ResourceType.ALL
    private var clickTimer: Timer? = null
    private val unusedSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
        var updateList: () -> Unit = {}
        var updateModuleBox: () -> Unit = {}

        val fileToModuleMap = mutableMapOf<VirtualFile, String>()
        var availableModules = listOf<String>()

        data class KotlinVectorItem(
            val iconName: String,
            val file: VirtualFile,
            val xmlContent: String,
            val moduleName: String,
            val icon: Icon
        )

        var allKotlinVectorItems = listOf<KotlinVectorItem>()

        val refreshData = {
            ResourceIconProvider.clearCache()
            com.github.mohamedshemees.kmpresourcesunfold.inspection.UnusedResourceDetector.clearCache()
            allFiles = KmpResourceScanner.findComposeDrawables(project)
            fileToModuleMap.clear()
            
            allFiles.forEach { file ->
                val name = ModuleUtilCore.findModuleForFile(file, project)?.name ?: "Unknown"
                val clean = name
                fileToModuleMap[file] = clean
            }
            
            val ktItems = mutableListOf<KotlinVectorItem>()
            allFiles.filter { it.extension?.lowercase() == "kt" }.forEach { file ->
                try {
                    val content = com.intellij.openapi.vfs.VfsUtilCore.loadText(file)
                    val icons = KotlinImageVectorParser.parseKotlinImageVectors(content)
                    val moduleName = fileToModuleMap[file] ?: "Unknown"
                    icons.forEach { iconItem ->
                        val targetSize = com.android.ide.common.vectordrawable.VdPreview.TargetSize.createFromMaxDimension(64)
                        val img = com.android.ide.common.vectordrawable.VdPreview.getPreviewFromVectorXml(targetSize, iconItem.xmlContent, StringBuilder())
                        val baseIcon = img?.let { ImageIcon(it) } ?: UIManager.getIcon("FileView.fileIcon")
                        val previewIcon = ResourceIconProvider.CheckerboardIcon(baseIcon, 64, 64)
                        ktItems.add(KotlinVectorItem(iconItem.name, file, iconItem.xmlContent, moduleName, previewIcon))
                    }
                } catch (_: Throwable) {}
            }
            allKotlinVectorItems = ktItems

            allStringResources = StringResourceProcessor.findStringResources(project, allFiles, fileToModuleMap)
            availableModules = fileToModuleMap.values.distinct().sorted()

            // Trigger background scan for unused resources
            val itemsToScan = mutableListOf<Pair<String, VirtualFile?>>()
            allFiles.filter { it.extension?.lowercase() != "kt" && !it.path.contains("values") }.forEach { file ->
                itemsToScan.add(Pair(file.nameWithoutExtension, file))
            }
            allKotlinVectorItems.forEach { item ->
                itemsToScan.add(Pair(item.iconName, item.file))
            }
            allStringResources.forEach { str ->
                itemsToScan.add(Pair(str.key, str.file))
            }
            com.github.mohamedshemees.kmpresourcesunfold.inspection.UnusedResourceDetector.scanUnusedAsync(project, itemsToScan) { unusedResult ->
                unusedSet.clear()
                unusedSet.addAll(unusedResult)
                updateList()
            }
        }

        refreshData()

        val actionGroup = DefaultActionGroup()
        val addAction = object : AnAction(MyBundle.message("action.import.title"), MyBundle.message("action.import.description"), com.intellij.icons.AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) {
                val popupGroup = DefaultActionGroup().apply {
                    add(object : AnAction("Drawable", "Import drawable assets (SVG/PNG/JPG)", com.intellij.icons.AllIcons.FileTypes.Image) {
                        override fun actionPerformed(evt: AnActionEvent) {
                            val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
                            descriptor.title = MyBundle.message("title.selectImages")
                            descriptor.withExtensionFilter(MyBundle.message("filter.images"), *ResourceExtension.allExtensions.toTypedArray() )
                            
                            val files = FileChooser.chooseFiles(descriptor, project, null)
                            if (files.isNotEmpty()) {
                                ImportDrawablesDialog(project, files.toList()).show()
                            }
                        }
                    })
                    add(object : AnAction("Google Fonts...", "Import Google Font family variants", com.intellij.icons.AllIcons.Actions.Edit) {
                        override fun actionPerformed(evt: AnActionEvent) {
                            ImportFontDialog(project).show()
                        }
                    })
                    add(object : AnAction("Local Font...", "Import font files from local device", com.intellij.icons.AllIcons.FileTypes.Any_type) {
                        override fun actionPerformed(evt: AnActionEvent) {
                            val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
                            descriptor.title = "Select Local Font Files"
                            descriptor.withExtensionFilter("Font Files (*.ttf, *.otf)", "ttf", "otf")

                            val files = FileChooser.chooseFiles(descriptor, project, null)
                            if (files.isNotEmpty()) {
                                ImportLocalFontsDialog(project, files.toList()).show()
                            }
                        }
                    })
                }
                
                val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                    null,
                    popupGroup,
                    e.dataContext,
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    true
                )
                
                val inputEvent = e.inputEvent
                if (inputEvent != null && inputEvent.component != null) {
                    popup.showUnderneathOf(inputEvent.component)
                } else {
                    popup.showInBestPositionFor(e.dataContext)
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

        val deleteUnusedAction = object : AnAction(
            "Delete Unused Drawables",
            "Delete all unused drawables and vector assets across the project",
            com.intellij.icons.AllIcons.Actions.GC
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val unusedFiles = mutableListOf<Pair<String, VirtualFile>>()
                val nonKt = allFiles.filter { it.extension?.lowercase() != "kt" && !it.path.contains("values") }
                nonKt.forEach { file ->
                    if (unusedSet.contains(file.nameWithoutExtension) || unusedSet.contains(file.path)) {
                        unusedFiles.add(Pair(file.name, file))
                    }
                }
                allKotlinVectorItems.forEach { item ->
                    if (unusedSet.contains(item.iconName) || unusedSet.contains(item.file.path)) {
                        if (unusedFiles.none { it.second == item.file }) {
                            unusedFiles.add(Pair(item.iconName, item.file))
                        }
                    }
                }

                if (unusedFiles.isEmpty()) {
                    Messages.showInfoMessage(project, "No unused drawables found in the project.", "Delete Unused Drawables")
                } else {
                    val totalBytes = unusedFiles.sumOf { it.second.length }
                    val formattedSize = ResourceUtils.formatSize(totalBytes)

                    val message = "Found ${unusedFiles.size} unused drawables ($formattedSize):\n\n" +
                            unusedFiles.take(8).joinToString("\n") { " • ${it.first} (${it.second.name})" } +
                            (if (unusedFiles.size > 8) "\n ... and ${unusedFiles.size - 8} more" else "") +
                            "\n\nAre you sure you want to delete these unused files?"

                    val confirm = Messages.showYesNoDialog(
                        project,
                        message,
                        "Delete Unused Drawables (${unusedFiles.size} files)",
                        "Delete All",
                        "Cancel",
                        Messages.getWarningIcon()
                    )

                    if (confirm == Messages.YES) {
                        val filesToDelete = unusedFiles.map { it.second }.toSet()
                        val count = unusedFiles.size

                        // 1. Immediately update UI state in memory for instant feedback (0ms lag)
                        allFiles = allFiles.filterNot { filesToDelete.contains(it) }
                        allKotlinVectorItems = allKotlinVectorItems.filterNot { filesToDelete.contains(it.file) }
                        unusedFiles.forEach { (name, file) ->
                            unusedSet.remove(name)
                            unusedSet.remove(file.path)
                            unusedSet.remove(file.nameWithoutExtension)
                        }
                        updateList()

                        // 2. Show lightweight toast notification
                        val targetComp = (e.inputEvent?.component as? JComponent) ?: mainPanel
                        JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(
                            "Deleted $count unused files ($formattedSize saved)",
                            com.intellij.openapi.ui.MessageType.INFO,
                            null
                        ).setFadeoutTime(3000).createBalloon().show(RelativePoint.getCenterOf(targetComp), Balloon.Position.above)

                        // 3. Perform file system deletion in background without holding UI locks
                        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                            val parents = mutableSetOf<VirtualFile>()
                            filesToDelete.forEach { vFile ->
                                try {
                                    vFile.parent?.let { parents.add(it) }
                                    val ioFile = java.io.File(vFile.path)
                                    if (ioFile.exists()) {
                                        ioFile.delete()
                                    }
                                } catch (_: Throwable) {}
                            }
                            if (parents.isNotEmpty()) {
                                com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(true, true, true, *parents.toTypedArray())
                            }
                        }
                    }
                }
            }
        }
        actionGroup.add(deleteUnusedAction)

        val toolbar = ActionManager.getInstance().createActionToolbar("KmpResourcesUnfoldToolbar", actionGroup, true)
        toolbar.targetComponent = mainPanel

        val topHeaderPanel = JPanel()
        topHeaderPanel.layout = BoxLayout(topHeaderPanel, BoxLayout.Y_AXIS)
        topHeaderPanel.border = BorderFactory.createEmptyBorder(6, 8, 6, 8)

        // Row 1: Action Toolbar (+ and Refresh buttons)
        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
            add(toolbar.component)
        }
        topHeaderPanel.add(actionRow)
        topHeaderPanel.add(Box.createVerticalStrut(6))

        // Row 2: Module Selection
        val moduleBox = ComboBox<ModuleOption>()
        moduleBox.isEditable = true
        val modulePanel = JPanel(BorderLayout(6, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
            add(JLabel(MyBundle.message("label.module")), BorderLayout.WEST)
            add(moduleBox, BorderLayout.CENTER)
        }
        topHeaderPanel.add(modulePanel)
        topHeaderPanel.add(Box.createVerticalStrut(6))

        // Row 3: Filter Chips
        val chipPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
        }
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
        chips.add(FilterChip(MyBundle.message("filter.fonts"), false, 0, onChipClicked))
        chips.add(FilterChip(MyBundle.message("filter.unused"), false, 0, onChipClicked))
        chips.forEach { chipPanel.add(it) }
        topHeaderPanel.add(chipPanel)
        topHeaderPanel.add(Box.createVerticalStrut(6))

        // Row 4: Search Bar
        val searchPanel = JPanel(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
        }
        val searchField = SearchTextField()
        searchPanel.add(searchField, BorderLayout.CENTER)
        topHeaderPanel.add(searchPanel)
        topHeaderPanel.add(Box.createVerticalStrut(6))

        // Row 5: Figma Bridge Port controls
        val portOptions = listOf(6789, 6790, 6791, 6792, 6793, 6794, 6795)
        val portBox = ComboBox<Int>()
        portOptions.forEach { portBox.addItem(it) }

        val figmaService = FigmaBridgeService.getInstance(project)
        val currentActive = figmaService.activePort ?: 6789
        portBox.selectedItem = currentActive

        val restartAction = object : AnAction("Relaunch Bridge", "Restart Figma Bridge on selected port", com.intellij.icons.AllIcons.Actions.Restart) {
            override fun actionPerformed(e: AnActionEvent) {
                val selectedPort = portBox.selectedItem as Int
                val newPort = figmaService.restartServer(selectedPort)
                if (newPort != null) {
                    portBox.selectedItem = newPort
                    JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(
                        "Figma Bridge restarted on port $newPort",
                        com.intellij.openapi.ui.MessageType.INFO,
                        null
                    ).setFadeoutTime(3000).createBalloon().show(RelativePoint.getCenterOf(portBox), Balloon.Position.above)
                } else {
                    Messages.showErrorDialog(project, "Could not start server on port $selectedPort", "Figma Bridge Error")
                }
            }
        }

        val restartBtn = com.intellij.openapi.actionSystem.impl.ActionButton(
            restartAction, 
            restartAction.templatePresentation.clone(), 
            "KmpResourcesUnfold", 
            Dimension(20, 20)
        )

        val portPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
        }
        val portLabel = JLabel("Figma Port:")
        portLabel.font = portLabel.font.deriveFont(10f)
        portPanel.add(portLabel)
        portPanel.add(portBox)
        portPanel.add(restartBtn)
        topHeaderPanel.add(portPanel)

        mainPanel.toolbar = topHeaderPanel

        project.messageBus.connect(toolWindow.disposable).apply {
            subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    UIUtil.invokeLaterIfNeeded {
                        if (!project.isDisposed) {
                            refreshData()
                            updateModuleBox()
                            updateList()
                        }
                    }
                }
            })
            subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
                override fun exitDumbMode() {
                    UIUtil.invokeLaterIfNeeded {
                        if (!project.isDisposed) {
                            refreshData()
                            updateModuleBox()
                            updateList()
                            com.intellij.ide.projectView.ProjectView.getInstance(project).refresh()
                        }
                    }
                }
            })
        }

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
                val cleanName = it
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

        editor.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                if (isFiltering) return
                val text = editor.text
                if (text.isNotEmpty()) {
                    val match = allModuleOptions.find { it.displayName == text }
                    if (match != null) {
                        isFiltering = true
                        moduleBox.selectedItem = match
                        isFiltering = false
                    }
                }
            }
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
                        is ResourceFileItem -> selected.file
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
                            is ResourceFileItem -> selected.file.nameWithoutExtension
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

            val nonKtFiles = allFiles.filter { it.extension?.lowercase() != "kt" }
            val filteredFiles = nonKtFiles.filter { file ->
                val ext = ResourceExtension.fromExtension(file.extension)
                val moduleName = fileToModuleMap[file] ?: "Unknown"
                val matchesModule = selectedModule == allModulesStr || moduleName == selectedModule
                val matchesType = when (currentTypeFilter) {
                    ResourceType.VECTORS -> ResourceExtension.vectorExtensions.contains(ext)
                    ResourceType.IMAGES -> ResourceExtension.imageExtensions.contains(ext)
                    ResourceType.FONTS -> ResourceExtension.fontExtensions.contains(ext)
                    ResourceType.STRINGS -> false
                    ResourceType.UNUSED -> unusedSet.contains(file.nameWithoutExtension) || unusedSet.contains(file.path)
                    else -> ext != ResourceExtension.XML || !file.path.contains("values")
                }
                val matchesSearch = file.name.lowercase().contains(lowerSearch)
                matchesModule && matchesSearch && matchesType
            }

            val filteredKotlinVectors = if (currentTypeFilter == ResourceType.ALL || currentTypeFilter == ResourceType.VECTORS || currentTypeFilter == ResourceType.UNUSED) {
                allKotlinVectorItems.filter { item ->
                    val matchesModule = selectedModule == allModulesStr || item.moduleName == selectedModule
                    val matchesSearch = item.iconName.lowercase().contains(lowerSearch) || item.file.name.lowercase().contains(lowerSearch)
                    val matchesUnused = currentTypeFilter != ResourceType.UNUSED || unusedSet.contains(item.iconName) || unusedSet.contains(item.file.path)
                    matchesModule && matchesSearch && matchesUnused
                }
            } else emptyList()

            val filteredStrings = if (currentTypeFilter == ResourceType.ALL || currentTypeFilter == ResourceType.STRINGS || currentTypeFilter == ResourceType.UNUSED) {
                allStringResources.filter { resource ->
                    val moduleName = resource.moduleName
                    val matchesModule = selectedModule == allModulesStr || moduleName == selectedModule
                    val matchesSearch = resource.key.lowercase().contains(lowerSearch)
                    val matchesUnused = currentTypeFilter != ResourceType.UNUSED || unusedSet.contains(resource.key)
                    matchesModule && matchesSearch && matchesUnused
                }
            } else emptyList()

            val filteredStringsFiles = if (currentTypeFilter == ResourceType.ALL || currentTypeFilter == ResourceType.STRINGS) {
                allFiles.filter { file ->
                    val moduleName = fileToModuleMap[file] ?: "Unknown"
                    val matchesModule = selectedModule == allModulesStr || moduleName == selectedModule
                    val matchesSearch = file.name.lowercase().contains(lowerSearch)
                    file.name == ResourceConstants.STRINGS_FILE && matchesModule && matchesSearch
                }
            } else emptyList()

            if (filteredKotlinVectors.isNotEmpty()) {
                filteredKotlinVectors.sortedBy { it.iconName }.forEach { item ->
                    val isUnused = unusedSet.contains(item.iconName) || unusedSet.contains(item.file.path)
                    val sub = "${item.moduleName} • Compose Vector"
                    resourceModel.addElement(ResourceParent(item.iconName, item.icon, item.file, sub, isUnused))
                }
            }

            if (filteredFiles.isNotEmpty()) {
                val densityOrder = listOf("Default", "LDPI", "MDPI", "HDPI", "XHDPI", "XXHDPI", "XXXHDPI")
                val groupedByName = filteredFiles.groupBy { it.nameWithoutExtension }
                
                groupedByName.keys.sorted().forEach { name ->
                    val files = groupedByName[name] ?: return@forEach
                    val sortedFiles = files.sortedWith(compareBy({ getDensity(it).let { d -> densityOrder.indexOf(d).takeIf { i -> i >= 0 } ?: 100 } }, { it.name }))
                    
                    val representative = sortedFiles.find { getDensity(it) == "Default" } ?: sortedFiles.first()
                    val moduleName = fileToModuleMap[representative] ?: "Unknown"
                    val isUnused = unusedSet.contains(name) || sortedFiles.any { unusedSet.contains(it.path) }

                    if (sortedFiles.size == 1) {
                        val file = sortedFiles.first()
                        val ext = file.extension?.uppercase() ?: "XML"
                        val density = getDensity(file)
                        val tag = if (density == "Default") ext else "$density • $ext"
                        val sub = "$moduleName • $tag"
                        resourceModel.addElement(ResourceParent(name, ResourceIconProvider.getIcon(file), file, sub, isUnused))
                    } else {
                        val sub = "$moduleName • ${sortedFiles.size} variants"
                        resourceModel.addElement(ResourceParent(name, ResourceIconProvider.getIcon(representative), representative, sub, isUnused))
                        sortedFiles.forEach { file ->
                            val mod = fileToModuleMap[file] ?: "Unknown"
                            resourceModel.addElement(ResourceChild(file, getDensity(file), mod))
                        }
                    }
                }
            }

            if (filteredStrings.isNotEmpty() || filteredStringsFiles.isNotEmpty()) {
                val stringsSub = if (filteredStrings.isNotEmpty()) "${filteredStrings.size} keys" else ""
                resourceModel.addElement(ResourceParent("Strings", UIManager.getIcon("FileView.fileIcon"), filteredStringsFiles.firstOrNull(), stringsSub))
                filteredStrings.sortedBy { it.key }.forEach { str ->
                    val isUnused = unusedSet.contains(str.key)
                    resourceModel.addElement(str.copy(isUnused = isUnused))
                }
                filteredStringsFiles.sortedBy { it.name }.forEach { file ->
                    val moduleName = fileToModuleMap[file] ?: "Unknown"
                    resourceModel.addElement(ResourceFileItem(file, moduleName))
                }
            }

            val totalResourcesCount = filteredFiles.size + filteredStrings.size + filteredStringsFiles.size + filteredKotlinVectors.size
            chips.forEach { chip ->
                val count = when (ResourceType.fromFilterName(chip.filterName)) {
                    ResourceType.ALL -> allFiles.size + allStringResources.size + allKotlinVectorItems.size
                    ResourceType.VECTORS -> allFiles.count { ResourceExtension.vectorExtensions.contains(ResourceExtension.fromExtension(it.extension)) } + allKotlinVectorItems.size
                    ResourceType.IMAGES -> allFiles.count { ResourceExtension.imageExtensions.contains(ResourceExtension.fromExtension(it.extension)) }
                    ResourceType.STRINGS -> allStringResources.size
                    ResourceType.FONTS -> allFiles.count { ResourceExtension.fontExtensions.contains(ResourceExtension.fromExtension(it.extension)) }
                    ResourceType.UNUSED -> allFiles.count { it.extension?.lowercase() != "kt" && !it.path.contains("values") && (unusedSet.contains(it.nameWithoutExtension) || unusedSet.contains(it.path)) } + allKotlinVectorItems.count { unusedSet.contains(it.iconName) || unusedSet.contains(it.file.path) } + allStringResources.count { unusedSet.contains(it.key) }
                }
                chip.updateCount(count)
            }
        }


        updateList()
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateList()
            override fun removeUpdate(e: DocumentEvent) = updateList()
            override fun changedUpdate(e: DocumentEvent) = updateList()
        })

        val contentPanel = JPanel(BorderLayout())
        contentPanel.add(JBScrollPane(resourceList), BorderLayout.CENTER)
        mainPanel.setContent(contentPanel)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(mainPanel, "", false))
    }
}
