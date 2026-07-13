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

    var familyName: String
    val variant: String

    val extension: String
        get() = file.extension ?: ""

    init {
        val (parsedFamily, parsedVariant) = getFamilyAndVariant(name)
        familyName = parsedFamily
        variant = parsedVariant
    }

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

        fun getFamilyAndVariant(name: String): Pair<String, String> {
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

    private val targetBox = ComboBox<Module>()
    private val pathField = JTextField()
    private val generateKotlinCb = JCheckBox("Generate Compose FontFamily / Typography code")
    private val kotlinPathField = JTextField()
    private val packageNameField = JTextField()

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

        val groupedItems = importedItems.groupBy { it.familyName }

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

        val labelMap = mutableMapOf<ImportedLocalFontItem, JBLabel>()

        if (isSummary) {
            headerPanel.add(JBLabel(name).apply { 
                font = font.deriveFont(Font.BOLD, 14f)
            }, BorderLayout.CENTER)
        } else {
            val nameField = JTextField(name).apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13f)
            }
            
            val updateNames = {
                val text = nameField.text.trim()
                if (text.isNotEmpty()) {
                    items.forEach { item ->
                        item.familyName = text
                        val baseName = normalizeLocalFontFilename(text, item.variant)
                        item.name = baseName
                        if (applyPrefix) {
                            item.applyPrefix(true)
                        }
                        if (item.customPrefixApplied.isNotEmpty()) {
                            val temp = item.customPrefixApplied
                            item.customPrefixApplied = ""
                            item.applyCustomPrefix(temp)
                        }
                        labelMap[item]?.text = "→  ${item.name}.${item.extension}"
                    }
                }
            }

            nameField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) { updateNames() }
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) { updateNames() }
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) { updateNames() }
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
            val (row, blueLabel) = createItemRow(item, isSummary, onUpdate)
            if (blueLabel != null) {
                labelMap[item] = blueLabel
            }
            itemsPanel.add(row)
        }
        groupPanel.add(itemsPanel, BorderLayout.CENTER)

        return groupPanel
    }

    private fun createItemRow(
        item: ImportedLocalFontItem, 
        isSummary: Boolean, 
        onUpdate: () -> Unit
    ): Pair<JPanel, JBLabel?> {
        val itemRowPanel = JPanel(BorderLayout(12, 0)).apply {
            border = JBUI.Borders.empty(5, 12)
            isOpaque = false
        }

        val infoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        var blueLabel: JBLabel? = null

        if (isSummary) {
            val selectedTarget = targetBox.selectedItem as? Module
            val resDirName = if (selectedTarget != null) {
                ResourceUtils.getComposeResourcesDir(selectedTarget)?.name ?: "composeResources"
            } else "composeResources"
            
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
            val label = JBLabel("→  ${item.name}.${item.extension}").apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD)
                foreground = com.intellij.ui.JBColor.blue
            }
            blueLabel = label
            val size = ResourceUtils.formatSize(item.file.length)

            val rowLayout = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                
                add(JBLabel("Variant: ${formatVariantName(item.variant)}  ").apply {
                    font = JBUI.Fonts.label()
                })
                
                add(label)
                
                add(JBLabel("  (Size: $size, Format: ${item.extension.uppercase()})").apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = UIUtil.getLabelDisabledForeground()
                })
            }
            infoPanel.add(rowLayout)
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

        return Pair(itemRowPanel, blueLabel)
    }

    private fun parseTargetText(text: String): Triple<String?, String, String?> {
        val modulePart = if (text.contains(":")) text.substringBefore(":").trim() else null
        val rightPart = if (text.contains(":")) text.substringAfter(":").trim() else text.trim()
        
        val typeSubParts = rightPart.split(".", limit = 2)
        val baseType = typeSubParts[0]
        val subPath = if (typeSubParts.size > 1) typeSubParts[1] else null
        
        return Triple(modulePart, baseType, subPath)
    }    private fun createStep2Panel(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(20)
        }

        val targetPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder("Destination Setup")
        }
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(5)
            weightx = 1.0
            gridx = 0
            gridy = 0
        }

        targetPanel.add(JBLabel("Target Module:"), gbc)

        val modules = ModuleManager.getInstance(project).modules.filter { m ->
            val name = m.name.lowercase()
            val hasResources = ResourceUtils.getComposeResourcesDir(m) != null || 
                               ResourceUtils.getAndroidResourcesDir(m) != null
            !name.contains("ios") && !name.contains("test") && hasResources
        }.sortedBy { it.name }

        targetBox.removeAllItems()
        modules.forEach { targetBox.addItem(it) }

        gbc.gridy = 1
        targetPanel.add(targetBox, gbc)

        val packageLabel = JBLabel("Package Name:")
        packageNameField.isOpaque = true

        val updateKotlinVisibility = {
            val enabled = generateKotlinCb.isSelected
            packageLabel.isEnabled = enabled
            packageNameField.isEnabled = enabled
        }
        generateKotlinCb.isOpaque = false
        generateKotlinCb.addActionListener { updateKotlinVisibility() }

        val updatePathField = {
            val module = targetBox.selectedItem as? Module
            if (module != null) {
                val resDir = ResourceUtils.getComposeResourcesDir(module) ?: ResourceUtils.getAndroidResourcesDir(module)
                if (resDir != null) {
                    pathField.text = File(resDir.path, "font").absolutePath
                } else {
                    val firstRoot = ModuleRootManager.getInstance(module).contentRoots.firstOrNull { r -> !r.path.contains("/build/") && !r.path.contains("\\build\\") }
                    if (firstRoot != null) {
                        pathField.text = File(firstRoot.path, "src/commonMain/composeResources/font").absolutePath
                    } else {
                        pathField.text = ""
                    }
                }
            } else {
                pathField.text = ""
            }
        }

        val updateKotlinPath = {
            val module = targetBox.selectedItem as? Module
            if (module != null) {
                val basePackage = ResourceUtils.findBasePackage(module)
                packageNameField.text = if (basePackage.isNotEmpty()) "$basePackage.theme" else "theme"

                val kotlinSrcDir = ResourceUtils.getKotlinSourceDir(module)
                if (kotlinSrcDir != null) {
                    val packagePath = if (basePackage.isNotEmpty()) basePackage.replace(".", "/") + "/theme" else "theme"
                    val fullPath = File(kotlinSrcDir, packagePath).absolutePath
                    kotlinPathField.text = fullPath
                } else {
                    kotlinPathField.text = ""
                }
            } else {
                kotlinPathField.text = ""
                packageNameField.text = ""
            }
        }

        targetBox.addActionListener {
            updatePathField()
            updateKotlinPath()
            refreshStep2()
        }
        updatePathField()
        updateKotlinPath()
        updateKotlinVisibility()

        gbc.gridy = 2
        targetPanel.add(generateKotlinCb, gbc)

        gbc.gridy = 3
        targetPanel.add(packageLabel, gbc)

        gbc.gridy = 4
        targetPanel.add(packageNameField, gbc)

        panel.add(targetPanel, BorderLayout.NORTH)

        val summaryPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        
        val scrollPane = JBScrollPane(summaryPanel).apply {
            border = BorderFactory.createTitledBorder("Import Summary")
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
        
        val groupedItems = importedItems.filter { it.isSelected }.groupBy { it.familyName }

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
        }
    }

    private fun formatVariantName(variant: String): String = when (variant) {
        "regular", "400" -> "Regular (400)"
        "italic", "400italic" -> "Italic (400italic)"
        "100" -> "Thin (100)"
        "100italic" -> "Thin Italic (100italic)"
        "200" -> "Extra Light (200)"
        "200italic" -> "Extra Light Italic (200italic)"
        "300" -> "Light (300)"
        "300italic" -> "Light Italic (300italic)"
        "500" -> "Medium (500)"
        "500italic" -> "Medium Italic (500italic)"
        "600" -> "Semi Bold (600)"
        "600italic" -> "Semi Bold Italic (600italic)"
        "700" -> "Bold (700)"
        "700italic" -> "Bold Italic (700italic)"
        "800" -> "Extra Bold (800)"
        "800italic" -> "Extra Bold Italic (800italic)"
        "900" -> "Black (900)"
        "900italic" -> "Black Italic (900italic)"
        else -> variant.replace("italic", " Italic").replaceFirstChar { it.uppercase() }
    }

    private fun normalizeLocalFontFilename(family: String, variant: String): String {
        val cleanFamily = family.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        val cleanVariant = variant.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return "${cleanFamily}_$cleanVariant"
    }

    private fun performImport() {
        val targetModule = targetBox.selectedItem as? Module ?: return
        val targetDir = File(pathField.text)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        
        val itemsToImport = importedItems.filter { it.isSelected }
        if (itemsToImport.isEmpty()) return

        val generateKotlin = generateKotlinCb.isSelected
        val kotlinDestPath = kotlinPathField.text
        val packageName = packageNameField.text

        val task = object : com.intellij.openapi.progress.Task.Backgroundable(project, "Importing Fonts", true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = false
                
                try {
                    itemsToImport.forEachIndexed { index, item ->
                        indicator.checkCanceled()
                        indicator.fraction = index.toDouble() / itemsToImport.size
                        indicator.text = "Importing ${item.name}..."

                        val targetFile = java.io.File(targetDir, "${item.name}.${item.extension}")
                        targetFile.writeBytes(item.file.contentsToByteArray())
                    }
                    
                    val resDirVirtual = ResourceUtils.getComposeResourcesDir(targetModule) ?: ResourceUtils.getAndroidResourcesDir(targetModule)
                    if (resDirVirtual != null) {
                        VfsUtil.markDirtyAndRefresh(true, true, true, resDirVirtual)
                    }

                    if (generateKotlin && kotlinDestPath.isNotEmpty()) {
                        val fontFamilies = mutableMapOf<String, MutableList<Pair<String, String>>>()
                        itemsToImport.forEach { item ->
                            val finalFileName = "${item.name}.${item.extension}"
                            val family = ImportedLocalFontItem.getFamilyAndVariant(item.name).first
                            val variant = item.variant
                            fontFamilies.getOrPut(family) { mutableListOf() }.add(finalFileName to variant)
                        }
                        try {
                            ResourceUtils.generateTypographyFile(
                                module = targetModule,
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
                    
                    UIUtil.invokeLaterIfNeeded {
                        val licenseBanner = MyBundle.message("font.browser.license.banner")
                        val notice = "Successfully imported ${itemsToImport.size} local font(s).\n\n$licenseBanner"
                        Messages.showInfoMessage(project, notice, "Import Complete")
                        close(OK_EXIT_CODE)
                    }
                } catch (e: Exception) {
                    UIUtil.invokeLaterIfNeeded {
                        Messages.showErrorDialog(project, e.message ?: MyBundle.message("error.unknown"), MyBundle.message("title.importError"))
                    }
                }
            }
        }

        com.intellij.openapi.progress.ProgressManager.getInstance().run(task)
    }
}
