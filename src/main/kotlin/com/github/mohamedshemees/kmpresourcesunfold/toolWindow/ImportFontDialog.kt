package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.MyBundle
import com.github.mohamedshemees.kmpresourcesunfold.ResourceUtils
import com.github.mohamedshemees.kmpresourcesunfold.font.FontCacheManager
import com.github.mohamedshemees.kmpresourcesunfold.font.GoogleWebfontItem
import com.github.mohamedshemees.kmpresourcesunfold.settings.FontSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ide.BrowserUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.*
import javax.swing.event.DocumentEvent

class ImportFontDialog(private val project: Project) : DialogWrapper(project) {

    private var allFonts = listOf<GoogleWebfontItem>()
    private var filteredFonts = listOf<GoogleWebfontItem>()
    private val variantCheckboxes = mutableMapOf<String, JCheckBox>()
    private var vfCheckbox: JCheckBox? = null
    private var cachedApiKey: String? = null
    private var searchTimer: Timer? = null

    // In-memory Font cache capped to recently used 150 entries to prevent memory growth
    private val loadedFonts = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Font>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Font>): Boolean {
                return size > 150
            }
        }
    )

    // Dedicated bounded thread pool for preview font loading to prevent resource starvation
    private val fontLoadExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)

    // Coalescing timer to batch list repaints to prevent high-frequency paint storms
    private val repaintTimer = Timer(80) {
        fontList.repaint()
    }.apply { isRepeats = false }

    // Track ongoing downloads to prevent duplicate network calls
    private val loadingFamilies = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private enum class ImportStep(val id: String) {
        SELECT_FAMILY("SelectFamilyStep"),
        RENAME_ITEMS("RenameItemsStep"),
        CHOOSE_TARGET("ChooseTargetStep")
    }

    private val selectedVariantsMap = mutableMapOf<String, MutableSet<String>>()
    private val selectedVfMap = mutableMapOf<String, Boolean>()
    private val currentFamilyVariants = mutableSetOf<String>()
    private var currentFamilyVf = false
    private val importedItems = mutableListOf<ImportedGoogleFontItem>()
    private var currentStep = ImportStep.SELECT_FAMILY
    private var applyPrefix = false
    private val mainPanel = JPanel(CardLayout())
    private lateinit var step1Panel: JPanel
    private lateinit var step2Panel: JPanel
    private lateinit var step3Panel: JPanel

    private val fontListModel = DefaultListModel<GoogleWebfontItem>()
    private val fontList = JBList(fontListModel)
    private val searchField = SearchTextField()

    // Filter selectors
    private val categoryBox = ComboBox<String>()
    private val sortBox = ComboBox<String>()

    private val detailsPanel = JPanel()
    private val familyLabel = JBLabel().apply { font = font.deriveFont(Font.BOLD, 18f) }
    private val categoryLabel = JBLabel().apply { foreground = UIUtil.getLabelDisabledForeground() }
    private val variantsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    private val targetBox = ComboBox<Module>()
    private val pathField = JTextField()
    private val generateKotlinCb = JCheckBox("Generate Compose FontFamily / Typography code")
    private val kotlinPathField = JTextField()
    private val packageNameField = JTextField()
    
    private val statusLabel = JBLabel()

    private val selectionsSummaryPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 4))
    private val summaryWrapper = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder("Selected Families")
        isOpaque = false
    }

    private val step2ListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty()
    }
    private val step2ScrollPane = JBScrollPane(step2ListPanel).apply {
        border = JBUI.Borders.empty()
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val previousAction = object : AbstractAction(MyBundle.message("button.previous")) {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            currentStep = when (currentStep) {
                ImportStep.SELECT_FAMILY -> ImportStep.SELECT_FAMILY
                ImportStep.RENAME_ITEMS -> {
                    selectedVariantsMap.clear()
                    selectedVfMap.clear()
                    importedItems.forEach { item ->
                        if (item.variant == "variable") {
                            selectedVfMap[item.familyName] = true
                        } else {
                            selectedVariantsMap.getOrPut(item.familyName) { mutableSetOf() }.add(item.variant)
                        }
                    }
                    ImportStep.SELECT_FAMILY
                }
                ImportStep.CHOOSE_TARGET -> ImportStep.RENAME_ITEMS
            }
            updateStep()
            if (currentStep == ImportStep.SELECT_FAMILY) {
                fontList.repaint()
                showDetails(fontList.selectedValue)
            }
        }
    }

    private val licenseMap = mapOf(
        "roboto" to "Apache License 2.0",
        "roboto mono" to "Apache License 2.0",
        "roboto slab" to "Apache License 2.0",
        "roboto condensed" to "Apache License 2.0",
        "noto sans" to "Apache License 2.0",
        "noto serif" to "Apache License 2.0",
        "open sans" to "Apache License 2.0",
        "ubuntu" to "Ubuntu Font License 1.0",
        "ubuntu mono" to "Ubuntu Font License 1.0",
        "ubuntu condensed" to "Ubuntu Font License 1.0"
    )

    init {
        title = MyBundle.message("font.import.title")
        
        // Populate Categories
        categoryBox.addItem("All Categories")
        categoryBox.addItem("Sans Serif")
        categoryBox.addItem("Serif")
        categoryBox.addItem("Monospace")
        categoryBox.addItem("Display")
        categoryBox.addItem("Handwriting")
        
        // Populate Sort Options (Google Fonts API sort capabilities)
        sortBox.addItem("Popularity")
        sortBox.addItem("Alphabetical")
        sortBox.addItem("Newest")
        sortBox.addItem("Number of Styles")
        sortBox.addItem("Trending")

        categoryBox.addActionListener { filterFonts() }
        sortBox.addActionListener { checkKeyAndLoadList(false) }

        init()
        checkKeyAndLoadList(false)
    }

    private fun getSelectedSortParam(): String = when (sortBox.selectedItem as? String) {
        "Alphabetical" -> "alpha"
        "Newest" -> "date"
        "Number of Styles" -> "style"
        "Trending" -> "trending"
        else -> "popularity"
    }

    private fun checkKeyAndLoadList(forceRefresh: Boolean) {
        statusLabel.text = "Checking credentials..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val apiKey = FontSettings.getApiKey()
            cachedApiKey = apiKey

            SwingUtilities.invokeLater {
                if (apiKey.isNullOrEmpty()) {
                    showApiKeySetupDialog(closeOnCancel = true)
                    return@invokeLater
                }

                val sortParam = getSelectedSortParam()
                statusLabel.text = "Loading fonts ($sortParam)..."
                ApplicationManager.getApplication().executeOnPooledThread {
                    var list = if (forceRefresh || FontCacheManager.isCacheExpired(sortParam)) null else FontCacheManager.getCachedFontList(sortParam)
                    if (list == null) {
                        list = FontCacheManager.fetchAndCacheFontList(apiKey, sortParam)
                    }

                    SwingUtilities.invokeLater {
                        if (list != null) {
                            allFonts = list.items
                            statusLabel.text = ""
                            filterFonts()
                        } else {
                            statusLabel.text = "Error fetching font list. Showing offline cache if available."
                            val cached = FontCacheManager.getCachedFontList(sortParam)
                            if (cached != null) {
                                allFonts = cached.items
                                filterFonts()
                            } else {
                                Messages.showErrorDialog(project, "Could not fetch font list. Please check your API key and internet connection.", "Error")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showApiKeySetupDialog(closeOnCancel: Boolean = false) {
        val dialog = FontApiKeyDialog(project, cachedApiKey)
        if (dialog.showAndGet()) {
            checkKeyAndLoadList(false)
        } else if (closeOnCancel) {
            close(CANCEL_EXIT_CODE)
        }
    }

    private fun filterFonts() {
        statusLabel.text = "Filtering..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val searchText = searchField.text.lowercase().trim()
            val selectedCategory = categoryBox.selectedItem as? String ?: "All Categories"
            
            val filtered = allFonts.filter { font ->
                val matchesSearch = searchText.isEmpty() || font.family.lowercase().contains(searchText)
                val matchesCategory = selectedCategory == "All Categories" || 
                    (font.category?.lowercase()?.replace("-", " ") == selectedCategory.lowercase())
                matchesSearch && matchesCategory
            }

            SwingUtilities.invokeLater {
                filteredFonts = filtered
                val newModel = DefaultListModel<GoogleWebfontItem>()
                filtered.forEach { newModel.addElement(it) }
                fontList.model = newModel
                statusLabel.text = ""
                if (filtered.isNotEmpty()) {
                    fontList.selectedIndex = 0
                } else {
                    showDetails(null)
                }
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        mainPanel.layout = CardLayout()
        mainPanel.preferredSize = Dimension(850, 550)

        step1Panel = createStep1Panel()
        step2Panel = createStep2Panel()
        step3Panel = createStep3Panel()

        mainPanel.add(step1Panel, ImportStep.SELECT_FAMILY.id)
        mainPanel.add(step2Panel, ImportStep.RENAME_ITEMS.id)
        mainPanel.add(step3Panel, ImportStep.CHOOSE_TARGET.id)

        updateStep()
        return mainPanel
    }

    private fun createStep1Panel(): JPanel {
        val panel = JPanel(BorderLayout(10, 0))

        // Left Panel (List + Search & Filters)
        val leftPanel = JPanel(BorderLayout(0, 10))
        leftPanel.border = JBUI.Borders.empty(10)

        val topFiltersPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(3)
            weightx = 1.0
            gridx = 0
            gridy = 0
        }

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                searchTimer?.stop()
                statusLabel.text = "Searching..."
                searchTimer = Timer(300) {
                    filterFonts()
                }.apply {
                    isRepeats = false
                    start()
                }
            }
        })
        gbc.gridwidth = 2
        topFiltersPanel.add(searchField, gbc)

        gbc.gridwidth = 1
        gbc.gridy = 1
        gbc.weightx = 0.5
        val catPanel = JPanel(BorderLayout(2, 0))
        catPanel.add(JBLabel(MyBundle.message("font.browser.filter.category")), BorderLayout.WEST)
        catPanel.add(categoryBox, BorderLayout.CENTER)
        topFiltersPanel.add(catPanel, gbc)

        gbc.gridx = 1
        val sortPanel = JPanel(BorderLayout(2, 0))
        sortPanel.add(JBLabel("Sort:"), BorderLayout.WEST)
        sortPanel.add(sortBox, BorderLayout.CENTER)
        topFiltersPanel.add(sortPanel, gbc)

        leftPanel.add(topFiltersPanel, BorderLayout.NORTH)

        fontList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val item = value as GoogleWebfontItem
                val selectedCount = selectedVariantsMap[item.family]?.size ?: 0
                val hasVf = selectedVfMap[item.family] == true
                val vfText = if (hasVf) "1 VF" else ""
                val staticText = if (selectedCount > 0) "$selectedCount styles" else ""
                val summaryText = listOf(vfText, staticText).filter { it.isNotEmpty() }.joinToString(" + ")
                val suffix = if (summaryText.isNotEmpty()) " [$summaryText selected]" else ""
                label.text = "${item.family}  (${item.category})$suffix"
                label.border = JBUI.Borders.empty(5, 8)

                val familyName = item.family
                val cachedFont = loadedFonts[familyName]
                if (cachedFont != null) {
                    label.font = cachedFont
                } else {
                    label.font = list?.font?.deriveFont(Font.PLAIN, 15f)
                    val menuUrl = item.menu
                    if (menuUrl != null && !loadingFamilies.contains(familyName)) {
                        loadingFamilies.add(familyName)
                        fontLoadExecutor.submit {
                            try {
                                val file = FontCacheManager.getOrDownloadPreviewFont(familyName, menuUrl)
                                if (file != null) {
                                    val awtFont = Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(Font.PLAIN, 15f)
                                    loadedFonts[familyName] = awtFont
                                    SwingUtilities.invokeLater {
                                        repaintTimer.restart()
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore
                            } finally {
                                loadingFamilies.remove(familyName)
                            }
                        }
                    }
                }
                return label
            }
        }

        fontList.addListSelectionListener {
            val selected = fontList.selectedValue
            showDetails(selected)
        }

        leftPanel.add(JBScrollPane(fontList), BorderLayout.CENTER)

        val bottomToolbar = JPanel(BorderLayout())
        bottomToolbar.add(statusLabel, BorderLayout.WEST)

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
        val refreshBtn = JButton(MyBundle.message("font.browser.refresh")).apply {
            addActionListener {
                checkKeyAndLoadList(true)
            }
        }
        val setKeyBtn = JButton("Configure API Key").apply {
            addActionListener {
                showApiKeySetupDialog()
            }
        }
        buttonsPanel.add(refreshBtn)
        buttonsPanel.add(setKeyBtn)
        bottomToolbar.add(buttonsPanel, BorderLayout.EAST)

        leftPanel.add(bottomToolbar, BorderLayout.SOUTH)

        // Right Panel (Details + Variants)
        detailsPanel.layout = BoxLayout(detailsPanel, BoxLayout.Y_AXIS)
        detailsPanel.border = JBUI.Borders.empty(10)

        val detailsScroll = JBScrollPane(detailsPanel).apply {
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        summaryWrapper.add(selectionsSummaryPanel, BorderLayout.CENTER)

        val summaryScroll = JBScrollPane(summaryWrapper).apply {
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = BorderFactory.createEmptyBorder()
            minimumSize = Dimension(0, 150)
        }

        val rightSplitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, detailsScroll, summaryScroll).apply {
            dividerLocation = 250
            resizeWeight = 0.5
        }

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightSplitPane).apply {
            dividerLocation = 400
            resizeWeight = 0.5
        }

        panel.add(splitPane, BorderLayout.CENTER)
        return panel
    }

    private fun updateSelectionsSummary() {
        selectionsSummaryPanel.removeAll()
        selectionsSummaryPanel.layout = BoxLayout(selectionsSummaryPanel, BoxLayout.Y_AXIS)
        
        var totalSelected = 0

        val grouped = mutableMapOf<String, MutableList<String>>()
        selectedVariantsMap.forEach { (family, variants) ->
            if (variants.isNotEmpty()) {
                grouped.getOrPut(family) { mutableListOf() }.addAll(variants)
                totalSelected += variants.size
            }
        }
        selectedVfMap.forEach { (family, hasVf) ->
            if (hasVf) {
                grouped.getOrPut(family) { mutableListOf() }.add("Variable Font (VF)")
                totalSelected++
            }
        }

        if (totalSelected == 0) {
            val emptyLabel = JBLabel("No fonts selected yet. Select styles above and click 'Add to Selection'.").apply {
                foreground = UIUtil.getLabelDisabledForeground()
                font = JBUI.Fonts.smallFont()
                border = JBUI.Borders.empty(15, 10)
                alignmentX = Component.CENTER_ALIGNMENT
            }
            selectionsSummaryPanel.add(emptyLabel)
        } else {
            grouped.forEach { (family, styles) ->
                val familyPanel = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(5, 5, 0, 5)
                }
                val familyLabel = JBLabel(family).apply {
                    font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13f)
                }
                familyPanel.add(familyLabel, BorderLayout.CENTER)

                val removeFamilyAction = object : AnAction(AllIcons.Actions.Close) {
                    override fun actionPerformed(e: AnActionEvent) {
                        selectedVariantsMap.remove(family)
                        selectedVfMap.remove(family)
                        val selected = fontList.selectedValue
                        if (selected?.family == family) {
                            currentFamilyVariants.clear()
                            currentFamilyVf = false
                        }
                        fontList.repaint()
                        showDetails(fontList.selectedValue)
                    }
                }
                val removeFamilyBtn = ActionButton(removeFamilyAction, removeFamilyAction.templatePresentation.clone(), "KmpResourcesUnfold", Dimension(16, 16))
                familyPanel.add(removeFamilyBtn, BorderLayout.EAST)

                familyPanel.maximumSize = Dimension(Int.MAX_VALUE, familyPanel.preferredSize.height)
                selectionsSummaryPanel.add(familyPanel)

                val stylesPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    border = JBUI.Borders.emptyLeft(15)
                }

                styles.forEach { style ->
                    val styleRow = JPanel(BorderLayout()).apply {
                        isOpaque = false
                        border = JBUI.Borders.empty(2, 5)
                    }
                    val label = if (style == "Variable Font (VF)") {
                        "• Variable Font (VF)"
                    } else {
                        "• ${formatVariantName(style)}"
                    }
                    styleRow.add(JBLabel(label).apply {
                        font = JBUI.Fonts.smallFont()
                        foreground = com.intellij.ui.JBColor.GRAY
                    }, BorderLayout.CENTER)

                    val removeStyleAction = object : AnAction(AllIcons.Actions.Close) {
                        override fun actionPerformed(e: AnActionEvent) {
                            if (style == "Variable Font (VF)") {
                                selectedVfMap.remove(family)
                                val selected = fontList.selectedValue
                                if (selected?.family == family) {
                                    currentFamilyVf = false
                                }
                            } else {
                                selectedVariantsMap[family]?.remove(style)
                                val selected = fontList.selectedValue
                                if (selected?.family == family) {
                                    currentFamilyVariants.remove(style)
                                }
                            }
                            fontList.repaint()
                            showDetails(fontList.selectedValue)
                        }
                    }
                    val removeStyleBtn = ActionButton(removeStyleAction, removeStyleAction.templatePresentation.clone(), "KmpResourcesUnfold", Dimension(12, 12))
                    styleRow.add(removeStyleBtn, BorderLayout.EAST)
                    styleRow.maximumSize = Dimension(Int.MAX_VALUE, styleRow.preferredSize.height)
                    stylesPanel.add(styleRow)
                }

                stylesPanel.maximumSize = Dimension(Int.MAX_VALUE, stylesPanel.preferredSize.height)
                selectionsSummaryPanel.add(stylesPanel)
                selectionsSummaryPanel.add(Box.createVerticalStrut(2))
            }
            selectionsSummaryPanel.add(Box.createVerticalGlue())
        }

        val border = summaryWrapper.border as? javax.swing.border.TitledBorder
        if (border != null) {
            border.title = "Selected Fonts ($totalSelected selections)"
        }

        selectionsSummaryPanel.revalidate()
        selectionsSummaryPanel.repaint()
        summaryWrapper.revalidate()
        summaryWrapper.repaint()
    }

    private fun showDetails(item: GoogleWebfontItem?) {
        detailsPanel.removeAll()
        if (item == null) {
            detailsPanel.revalidate()
            detailsPanel.repaint()
            return
        }

        familyLabel.text = item.family
        categoryLabel.text = "Category: ${item.category}"

        detailsPanel.add(familyLabel)
        detailsPanel.add(categoryLabel)
        detailsPanel.add(Box.createVerticalStrut(15))

        // Populate temporary variables from committed selections, or default to regular style
        currentFamilyVariants.clear()
        if (selectedVariantsMap.containsKey(item.family)) {
            currentFamilyVariants.addAll(selectedVariantsMap[item.family] ?: emptySet())
        } else {
            if (item.variants.contains("regular")) {
                currentFamilyVariants.add("regular")
            }
        }
        currentFamilyVf = selectedVfMap[item.family] == true

        // Variants selection
        variantsPanel.removeAll()

        val selectAllCb = JCheckBox("Select All").apply {
            isOpaque = false
            isSelected = item.variants.all { currentFamilyVariants.contains(it) }
        }

        val addBtn = JButton("Add to Selection", AllIcons.General.Add).apply {
            addActionListener {
                if (currentFamilyVariants.isEmpty() && !currentFamilyVf) {
                    Messages.showWarningDialog(project, "Please select at least one variant or check 'Variable Font'.", "No Variants Selected")
                    return@addActionListener
                }

                selectedVariantsMap[item.family] = currentFamilyVariants.toMutableSet()
                selectedVfMap[item.family] = currentFamilyVf

                fontList.repaint()
                updateSelectionsSummary()
            }
        }

        selectAllCb.addActionListener {
            val selected = selectAllCb.isSelected
            variantCheckboxes.forEach { (variant, cb) ->
                cb.isSelected = selected
                if (selected) {
                    currentFamilyVariants.add(variant)
                } else {
                    currentFamilyVariants.remove(variant)
                }
            }
        }

        val topActionsPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(selectAllCb, BorderLayout.WEST)
            add(addBtn, BorderLayout.EAST)
        }

        variantsPanel.add(topActionsPanel)
        variantsPanel.add(Box.createVerticalStrut(5))

        val borderPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Select Variants to Import")
        }

        val checkboxContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        variantCheckboxes.clear()
        vfCheckbox = null

        var baseFont = loadedFonts[item.family]
        if (baseFont == null) {
            val file = item.menu?.let { FontCacheManager.getOrDownloadPreviewFont(item.family, it) }
            if (file != null && file.exists()) {
                try {
                    baseFont = Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(Font.PLAIN, 15f)
                    loadedFonts[item.family] = baseFont
                } catch (e: java.lang.Exception) {}
            }
        }

        item.variants.forEach { variant ->
            val name = formatVariantName(variant)
            val cb = JCheckBox(name, currentFamilyVariants.contains(variant)).apply {
                isOpaque = false
                addActionListener {
                    if (isSelected) {
                        currentFamilyVariants.add(variant)
                    } else {
                        currentFamilyVariants.remove(variant)
                    }
                    selectAllCb.isSelected = item.variants.all { currentFamilyVariants.contains(it) }
                }
            }
            variantCheckboxes[variant] = cb

            val row = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 5, 0)).apply {
                isOpaque = false
                add(cb)

                if (baseFont != null) {
                    val styledFont = deriveVariantFont(baseFont, variant).deriveFont(16f)
                    val previewLabel = JBLabel("  -  ${item.family}").apply {
                        font = styledFont
                        foreground = com.intellij.ui.JBColor.GRAY
                    }
                    add(previewLabel)
                }
            }
            checkboxContainer.add(row)
        }

        borderPanel.add(checkboxContainer, BorderLayout.CENTER)
        variantsPanel.add(borderPanel)

        // Variable font capability
        val apiKey = cachedApiKey
        if (apiKey != null && item.axes != null || (item.variants.any { it.contains("variable") })) {
            val vfPanel = JPanel(BorderLayout()).apply {
                border = BorderFactory.createTitledBorder("Variable Font (VF) Options")
            }
            val vfContainer = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }
            val cb = JCheckBox(MyBundle.message("font.browser.variable"), currentFamilyVf).apply {
                isOpaque = false
                addActionListener {
                    currentFamilyVf = isSelected
                }
            }
            vfCheckbox = cb
            vfContainer.add(cb)

            // Let's fetch variable font capability asynchronously if axes is not resolved
            if (item.axes == null && apiKey != null) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    val detailedItem = FontCacheManager.fetchVariableFontDetails(apiKey, item.family)
                    if (detailedItem?.axes != null) {
                        SwingUtilities.invokeLater {
                            vfContainer.removeAll()
                            vfContainer.add(cb)
                            detailedItem.axes.forEach { axis ->
                                vfContainer.add(JBLabel("  Axis: ${axis.tag} (${axis.start} - ${axis.end})").apply {
                                    font = font.deriveFont(Font.ITALIC)
                                    foreground = UIUtil.getLabelDisabledForeground()
                                })
                            }
                            vfPanel.revalidate()
                            vfPanel.repaint()
                        }
                    }
                }
            } else {
                item.axes?.forEach { axis ->
                    vfContainer.add(JBLabel("  Axis: ${axis.tag} (${axis.start} - ${axis.end})").apply {
                        font = font.deriveFont(Font.ITALIC)
                        foreground = UIUtil.getLabelDisabledForeground()
                    })
                }
            }

            vfPanel.add(vfContainer, BorderLayout.CENTER)
            variantsPanel.add(Box.createVerticalStrut(10))
            variantsPanel.add(vfPanel)
        }

        detailsPanel.add(variantsPanel)
        detailsPanel.add(Box.createVerticalStrut(15))
        detailsPanel.revalidate()
        detailsPanel.repaint()
        updateSelectionsSummary()
    }

    private fun deriveVariantFont(baseFont: Font, variant: String): Font {
        val isItalic = variant.contains("italic")
        val isBold = variant.contains("700") || variant.contains("800") || variant.contains("900") || variant.contains("bold")

        var style = Font.PLAIN
        if (isBold && isItalic) {
            style = Font.BOLD or Font.ITALIC
        } else if (isBold) {
            style = Font.BOLD
        } else if (isItalic) {
            style = Font.ITALIC
        }

        return baseFont.deriveFont(style)
    }

    private fun updatePathField() {
        val module = targetBox.selectedItem as? Module ?: return
        val resDirVirtual = ResourceUtils.getComposeResourcesDir(module)
        if (resDirVirtual != null) {
            pathField.text = File(resDirVirtual.path, "font").absolutePath
        } else {
            pathField.text = ""
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

    private fun normalizeFontFilename(familyName: String, variant: String): String {
        val cleanFamily = familyName.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')

        val cleanVariant = when (variant) {
            "regular", "400" -> "regular"
            "italic", "400italic" -> "italic"
            "100" -> "thin"
            "100italic" -> "thin_italic"
            "200" -> "extra_light"
            "200italic" -> "extra_light_italic"
            "300" -> "light"
            "300italic" -> "light_italic"
            "500" -> "medium"
            "500italic" -> "medium_italic"
            "600" -> "semi_bold"
            "600italic" -> "semi_bold_italic"
            "700" -> "bold"
            "700italic" -> "bold_italic"
            "800" -> "extra_bold"
            "800italic" -> "extra_bold_italic"
            "900" -> "black"
            "900italic" -> "black_italic"
            else -> variant.lowercase()
                .replace("italic", "_italic")
                .replace(Regex("[^a-z0-9]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
        }

        return "${cleanFamily}_$cleanVariant"
    }

    override fun doOKAction() {
        when (currentStep) {
            ImportStep.SELECT_FAMILY -> {
                val apiKey = cachedApiKey ?: return

                val items = mutableListOf<ImportedGoogleFontItem>()
                selectedVariantsMap.forEach { (familyName, variants) ->
                    val fontItem = allFonts.find { it.family == familyName } ?: return@forEach
                    val downloadVf = selectedVfMap[familyName] == true

                    if (downloadVf) {
                        val originalName = "${familyName.lowercase().replace(" ", "_")}_variable"
                        items.add(ImportedGoogleFontItem(
                            familyName = familyName,
                            variant = "variable",
                            downloadUrl = "",
                            originalName = originalName
                        ))
                    }
                    variants.forEach { variant ->
                        val url = fontItem.files?.get(variant) ?: ""
                        items.add(ImportedGoogleFontItem(
                            familyName = familyName,
                            variant = variant,
                            downloadUrl = url,
                            originalName = normalizeFontFilename(familyName, variant)
                        ))
                    }
                }

                if (items.isEmpty()) {
                    Messages.showWarningDialog(project, "Please select at least one static variant or check 'Variable Font' for any font family.", "No Variants Selected")
                    return
                }

                importedItems.clear()
                importedItems.addAll(items)

                currentStep = ImportStep.RENAME_ITEMS
                updateStep()
            }
            ImportStep.RENAME_ITEMS -> {
                if (importedItems.none { it.isSelected }) {
                    Messages.showWarningDialog(project, "Please select at least one variant to import.", "No Variants Selected")
                    return
                }
                currentStep = ImportStep.CHOOSE_TARGET
                updateStep()
            }
            ImportStep.CHOOSE_TARGET -> {
                performImport()
            }
        }
    }

    private fun performImport() {
        val apiKey = cachedApiKey ?: return
        val itemsToImport = importedItems.filter { it.isSelected }
        if (itemsToImport.isEmpty()) return

        val targetDir = File(pathField.text)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val generateKotlin = generateKotlinCb.isSelected
        val kotlinDestPath = kotlinPathField.text
        val packageName = packageNameField.text
        val targetModule = targetBox.selectedItem as? Module

        statusLabel.text = MyBundle.message("font.browser.downloading")
        
        ApplicationManager.getApplication().executeOnPooledThread {
            var successCount = 0
            var failCount = 0
            val importedFontFiles = mutableListOf<Triple<String, String, String>>()

            itemsToImport.forEachIndexed { index, item ->
                if (item.variant == "variable") {
                    val detailed = FontCacheManager.fetchVariableFontDetails(apiKey, item.familyName)
                    val vfUrl = detailed?.files?.get("regular") ?: detailed?.files?.values?.firstOrNull()
                    if (vfUrl != null) {
                        val downloaded = FontCacheManager.downloadFontFile(vfUrl, item.familyName, "variable")
                        if (downloaded != null) {
                            try {
                                val destName = "${item.name}.${item.extension}"
                                val dest = File(targetDir, destName)
                                Files.copy(downloaded.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                successCount++
                                importedFontFiles.add(Triple(item.familyName, destName, "variable"))
                            } catch (e: IOException) {
                                failCount++
                            }
                        } else failCount++
                    } else failCount++
                } else {
                    val staticUrl = item.downloadUrl
                    if (staticUrl.isNotEmpty()) {
                        val downloaded = FontCacheManager.downloadFontFile(staticUrl, item.familyName, item.variant)
                        if (downloaded != null) {
                            try {
                                val destName = "${item.name}.${item.extension}"
                                val dest = File(targetDir, destName)
                                Files.copy(downloaded.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                successCount++
                                importedFontFiles.add(Triple(item.familyName, destName, item.variant))
                            } catch (e: IOException) {
                                failCount++
                            }
                        } else failCount++
                    } else failCount++
                }
            }

            if (successCount > 0 && generateKotlin && targetModule != null && kotlinDestPath.isNotEmpty()) {
                try {
                    val fontFamilies = importedFontFiles.groupBy(
                        keySelector = { it.first },
                        valueTransform = { it.second to it.third }
                    )
                    ResourceUtils.generateTypographyFile(
                        module = targetModule,
                        destinationFolder = File(kotlinDestPath),
                        packageName = packageName,
                        fontFamilies = fontFamilies
                    )
                } catch (e: Exception) {
                    // Ignore
                }
            }

            SwingUtilities.invokeLater {
                statusLabel.text = ""
                val virtualFolder = VfsUtil.findFileByIoFile(targetDir, true)
                if (virtualFolder != null) {
                    VfsUtil.markDirtyAndRefresh(true, true, true, virtualFolder)
                }
                if (generateKotlin && kotlinDestPath.isNotEmpty()) {
                    val virtualKotlinFolder = VfsUtil.findFileByIoFile(File(kotlinDestPath), true)
                    if (virtualKotlinFolder != null) {
                        VfsUtil.markDirtyAndRefresh(true, true, true, virtualKotlinFolder)
                    }
                }

                if (successCount > 0) {
                    val selectedItem = fontList.selectedValue
                    val licenseType = if (selectedItem != null) {
                        licenseMap[selectedItem.family.lowercase()] ?: "SIL Open Font License 1.1 (OFL)"
                    } else "SIL Open Font License 1.1 (OFL)"
                    
                    val notice = "${MyBundle.message("font.browser.import.success", successCount)}\n\n" +
                            "License Info: Licensed under $licenseType.\n" +
                            MyBundle.message("font.browser.license.banner")
                    Messages.showInfoMessage(project, notice, "Fonts Imported")
                    close(OK_EXIT_CODE)
                } else {
                    Messages.showErrorDialog(project, MyBundle.message("font.browser.import.error"), "Import Failed")
                }
            }
        }
    }

    private fun createStep2Panel(): JPanel {
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
                                refreshStep2(panel)
                            }
                        }
                    }
                    add(deleteSelected)
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
                        refreshStep2(panel)
                    }
                }
                add(selectAllCheckbox)

                val prefixCheckbox = JCheckBox("Apply standard prefix (font_)", applyPrefix).apply {
                    isOpaque = false
                    addActionListener {
                        applyPrefix = isSelected
                        importedItems.filter { it.isSelected }.forEach { it.applyPrefix(applyPrefix) }
                        refreshStep2(panel)
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
                        refreshStep2(panel)
                    }
                }
                add(applyCustomPrefixBtn)
            }
            add(row2)
        }

        panel.add(header, BorderLayout.NORTH)
        panel.add(step2ScrollPane, BorderLayout.CENTER)

        refreshStep2(panel)
        return panel
    }

    private fun refreshStep2(panel: JPanel) {
        val header = panel.getComponent(0) as JPanel
        
        val row1 = header.getComponent(0) as JPanel
        val countLabel = row1.getComponent(0) as JBLabel
        val count = importedItems.size
        countLabel.text = if (count == 1) "Step 2: Select Font to Import" else "Step 2: Select Fonts to Import ($count selected)"

        val rightActionsPanel = row1.getComponent(1) as JPanel
        val deleteSelected = rightActionsPanel.getComponent(0) as JButton
        deleteSelected.isEnabled = importedItems.any { it.isSelected }

        val row2 = header.getComponent(2) as JPanel
        val selectAllCheckbox = row2.getComponent(0) as JCheckBox
        selectAllCheckbox.isSelected = importedItems.isNotEmpty() && importedItems.all { it.isSelected }

        val scrollPos = step2ScrollPane.verticalScrollBar.value

        step2ListPanel.removeAll()

        val groupedItems = importedItems.groupBy { it.familyName }

        groupedItems.forEach { (name, items) ->
            val itemPanel = createGroupPanel(name, items, isSummary = false) { refreshStep2(panel) }
            itemPanel.maximumSize = Dimension(Int.MAX_VALUE, itemPanel.preferredSize.height)
            step2ListPanel.add(itemPanel)
            val sep = JSeparator()
            sep.maximumSize = Dimension(Int.MAX_VALUE, 1)
            step2ListPanel.add(sep)
        }

        step2ListPanel.add(Box.createVerticalGlue())
        
        step2ListPanel.revalidate()
        step2ListPanel.repaint()
        
        SwingUtilities.invokeLater {
            step2ScrollPane.verticalScrollBar.value = scrollPos
        }
    }

    private fun createGroupPanel(
        name: String, 
        items: List<ImportedGoogleFontItem>, 
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
                    val text = nameField.text.trim()
                    if (text.isNotEmpty()) {
                        items.forEach { item ->
                            val baseName = normalizeFontFilename(text, item.variant)
                            item.name = baseName
                            if (applyPrefix) {
                                item.applyPrefix(true)
                            }
                            if (item.customPrefixApplied.isNotEmpty()) {
                                val temp = item.customPrefixApplied
                                item.customPrefixApplied = ""
                                item.applyCustomPrefix(temp)
                            }
                        }
                        onUpdate()
                    }
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
        item: ImportedGoogleFontItem, 
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
            val rowLayout = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                
                add(JBLabel("Variant: ${formatVariantName(item.variant)}  ").apply {
                    font = JBUI.Fonts.label()
                })
                
                add(JBLabel("→  ${item.name}.${item.extension}").apply {
                    font = JBUI.Fonts.label().deriveFont(Font.BOLD)
                    foreground = com.intellij.ui.JBColor.blue
                })
                
                add(JBLabel("  (Format: ${item.extension.uppercase()})").apply {
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

        return itemRowPanel
    }

    private fun createStep3Panel(): JPanel {
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

        gbc.gridy = 2
        targetPanel.add(JBLabel("Target Folder Path:"), gbc)

        val pathPanel = JPanel(BorderLayout(5, 0))
        pathField.isEditable = false
        updatePathField()

        pathPanel.add(pathField, BorderLayout.CENTER)
        
        val browseBtn = JButton("Browse...").apply {
            addActionListener {
                val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
                descriptor.title = "Select Font Destination Folder"
                val initialFile = VfsUtil.findFileByIoFile(File(pathField.text), true)
                val chosen = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, initialFile)
                if (chosen != null) {
                    pathField.text = chosen.path
                }
            }
        }
        pathPanel.add(browseBtn, BorderLayout.EAST)

        gbc.gridy = 3
        targetPanel.add(pathPanel, gbc)

        val kotlinFolderLabel = JBLabel("Kotlin Source Folder:")
        kotlinPathField.isEditable = false
        val packageLabel = JBLabel("Package Name:")
        packageNameField.isOpaque = true

        val updateKotlinVisibility = {
            val enabled = generateKotlinCb.isSelected
            kotlinFolderLabel.isEnabled = enabled
            kotlinPathField.isEnabled = enabled
            packageLabel.isEnabled = enabled
            packageNameField.isEnabled = enabled
        }
        generateKotlinCb.isOpaque = false
        generateKotlinCb.addActionListener { updateKotlinVisibility() }

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
            refreshStep3()
        }
        updatePathField()
        updateKotlinPath()
        updateKotlinVisibility()

        gbc.gridy = 4
        targetPanel.add(generateKotlinCb, gbc)

        gbc.gridy = 5
        targetPanel.add(kotlinFolderLabel, gbc)

        val kotlinPathPanel = JPanel(BorderLayout(5, 0))
        kotlinPathPanel.add(kotlinPathField, BorderLayout.CENTER)

        val kotlinBrowseBtn = JButton("Browse...").apply {
            addActionListener {
                val module = targetBox.selectedItem as? Module ?: return@addActionListener
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
        kotlinPathPanel.add(kotlinBrowseBtn, BorderLayout.EAST)

        gbc.gridy = 6
        targetPanel.add(kotlinPathPanel, gbc)

        gbc.gridy = 7
        targetPanel.add(packageLabel, gbc)

        gbc.gridy = 8
        targetPanel.add(packageNameField, gbc)

        panel.add(targetPanel, BorderLayout.NORTH)

        val summaryPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        
        val scrollPane = JBScrollPane(summaryPanel).apply {
            border = BorderFactory.createTitledBorder("Import Summary")
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun refreshStep3() {
        if (!::step3Panel.isInitialized) return
        
        val scrollPane = step3Panel.getComponent(1) as JBScrollPane
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

    private fun updateStep() {
        val layout = mainPanel.layout as CardLayout
        when (currentStep) {
            ImportStep.SELECT_FAMILY -> {
                layout.show(mainPanel, ImportStep.SELECT_FAMILY.id)
                setOKButtonText(MyBundle.message("button.next"))
                previousAction.isEnabled = false
            }
            ImportStep.RENAME_ITEMS -> {
                layout.show(mainPanel, ImportStep.RENAME_ITEMS.id)
                setOKButtonText(MyBundle.message("button.next"))
                previousAction.isEnabled = true
                val panel2 = step2Panel
                refreshStep2(panel2)
            }
            ImportStep.CHOOSE_TARGET -> {
                layout.show(mainPanel, ImportStep.CHOOSE_TARGET.id)
                setOKButtonText(MyBundle.message("button.import_single"))
                previousAction.isEnabled = true
                refreshStep3()
            }
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(previousAction, okAction, cancelAction)
    }

    override fun dispose() {
        fontLoadExecutor.shutdownNow()
        super.dispose()
    }
}

// Dialog to setup the API key
class FontApiKeyDialog(project: Project, private val initialKey: String?) : DialogWrapper(project) {
    private val keyField = JTextField()
    
    init {
        title = MyBundle.message("font.setup.apikey.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(5)
            weightx = 1.0
            gridx = 0
            gridy = 0
        }

        panel.add(JBLabel(MyBundle.message("font.setup.apikey.label")), gbc)

        keyField.text = initialKey ?: ""
        gbc.gridy = 1
        panel.add(keyField, gbc)

        val helpPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            val label1 = JBLabel(MyBundle.message("font.setup.apikey.help.prefix")).apply {
                foreground = UIUtil.getLabelDisabledForeground()
                font = JBUI.Fonts.smallFont()
            }
            val link = LinkLabel<Any>(MyBundle.message("font.setup.apikey.help.link"), null) { _, _ ->
                BrowserUtil.browse("https://developers.google.com/fonts/docs/developer_api#identifying_your_application_to_google")
            }.apply {
                font = JBUI.Fonts.smallFont()
            }
            val label2 = JBLabel(MyBundle.message("font.setup.apikey.help.suffix")).apply {
                foreground = UIUtil.getLabelDisabledForeground()
                font = JBUI.Fonts.smallFont()
            }
            add(label1)
            add(link)
            add(label2)
        }
        gbc.gridy = 2
        panel.add(helpPanel, gbc)

        panel.preferredSize = Dimension(450, 130)
        return panel
    }

    override fun doOKAction() {
        val key = keyField.text.trim()
        if (key.isEmpty()) {
            Messages.showErrorDialog(contentPanel, "API Key cannot be empty.", "Error")
            return
        }

        // Validate Key
        val dialog = this
        dialog.setOKActionEnabled(false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val isValid = FontCacheManager.validateApiKey(key)
            SwingUtilities.invokeLater {
                dialog.setOKActionEnabled(true)
                if (isValid) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        FontSettings.saveApiKey(key)
                        SwingUtilities.invokeLater {
                            dialog.close(OK_EXIT_CODE)
                        }
                    }
                } else {
                    Messages.showErrorDialog(dialog.contentPanel, MyBundle.message("font.setup.apikey.error"), "Validation Failed")
                }
            }
        }
    }
}

data class ImportedGoogleFontItem(
    val familyName: String,
    val variant: String,
    val downloadUrl: String,
    private val originalName: String,
    var isSelected: Boolean = true
) {
    var name: String = originalName
    var customPrefixApplied: String = ""

    val extension: String = "ttf"

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
}
