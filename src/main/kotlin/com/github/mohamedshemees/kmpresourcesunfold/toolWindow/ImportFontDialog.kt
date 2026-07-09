package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.MyBundle
import com.github.mohamedshemees.kmpresourcesunfold.ResourceUtils
import com.github.mohamedshemees.kmpresourcesunfold.font.FontCacheManager
import com.github.mohamedshemees.kmpresourcesunfold.font.GoogleWebfontItem
import com.github.mohamedshemees.kmpresourcesunfold.font.GoogleWebfontList
import com.github.mohamedshemees.kmpresourcesunfold.settings.FontSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
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

    private val mainPanel = JPanel(BorderLayout(10, 0))
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
    
    private val statusLabel = JBLabel()

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
                    showApiKeySetupDialog()
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

    private fun showApiKeySetupDialog() {
        val dialog = FontApiKeyDialog(project, cachedApiKey)
        if (dialog.showAndGet()) {
            checkKeyAndLoadList(false)
        } else {
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
        mainPanel.preferredSize = Dimension(850, 550)

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
                label.text = "${item.family}  (${item.category})"
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
                                // Ignore or log via standard mechanisms
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

        // Right Panel (Details + Variants + Destination)
        detailsPanel.layout = BoxLayout(detailsPanel, BoxLayout.Y_AXIS)
        detailsPanel.border = JBUI.Borders.empty(10)

        val detailsScroll = JBScrollPane(detailsPanel).apply {
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, detailsScroll).apply {
            dividerLocation = 400
            resizeWeight = 0.5
        }

        mainPanel.add(splitPane, BorderLayout.CENTER)
        return mainPanel
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

        // Variants selection
        variantsPanel.removeAll()
        val borderPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Select Variants to Import")
        }

        val checkboxContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        variantCheckboxes.clear()
        vfCheckbox = null

        val selectAllCb = JCheckBox("Select All").apply {
            isOpaque = false
            isSelected = item.variants.all { it == "regular" && item.variants.size == 1 }
        }

        selectAllCb.addActionListener {
            val selected = selectAllCb.isSelected
            variantCheckboxes.values.forEach { cb ->
                cb.isSelected = selected
            }
        }

        checkboxContainer.add(selectAllCb)
        checkboxContainer.add(JSeparator().apply { maximumSize = Dimension(Int.MAX_VALUE, 1) })
        checkboxContainer.add(Box.createVerticalStrut(5))

        item.variants.forEach { variant ->
            val name = formatVariantName(variant)
            val cb = JCheckBox(name, variant == "regular").apply {
                isOpaque = false
                addActionListener {
                    selectAllCb.isSelected = variantCheckboxes.values.all { it.isSelected }
                }
            }
            variantCheckboxes[variant] = cb
            checkboxContainer.add(cb)
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
            val cb = JCheckBox(MyBundle.message("font.browser.variable")).apply {
                isOpaque = false
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

        // Target Module Layout
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
            val hasResources = ResourceUtils.getComposeResourcesDir(m, project) != null || 
                               ResourceUtils.getAndroidResourcesDir(m, project) != null
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
        targetBox.addActionListener { updatePathField() }

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

        detailsPanel.add(targetPanel)
        detailsPanel.revalidate()
        detailsPanel.repaint()
    }

    private fun updatePathField() {
        val module = targetBox.selectedItem as? Module ?: return
        val resDirVirtual = ResourceUtils.getComposeResourcesDir(module, project)
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
        val selectedItem = fontList.selectedValue ?: return
        val apiKey = cachedApiKey ?: return

        // Collect checked variants
        val checkedVariants = mutableListOf<String>()
        variantCheckboxes.forEach { (variant, cb) ->
            if (cb.isSelected) {
                checkedVariants.add(variant)
            }
        }

        // Variable Font selection
        val downloadVf = vfCheckbox?.isSelected ?: false

        if (checkedVariants.isEmpty() && !downloadVf) {
            Messages.showWarningDialog(project, "Please select at least one static variant or check 'Variable Font'.", "No Variants Selected")
            return
        }

        val targetDir = File(pathField.text)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        statusLabel.text = MyBundle.message("font.browser.downloading")
        
        ApplicationManager.getApplication().executeOnPooledThread {
            var successCount = 0
            var failCount = 0

            // 1. Download Variable Font if checked
            if (downloadVf) {
                val detailed = FontCacheManager.fetchVariableFontDetails(apiKey, selectedItem.family)
                val vfUrl = detailed?.files?.get("regular") ?: detailed?.files?.values?.firstOrNull()
                if (vfUrl != null) {
                    val downloaded = FontCacheManager.downloadFontFile(vfUrl, selectedItem.family, "variable")
                    if (downloaded != null) {
                        try {
                            val dest = File(targetDir, "${selectedItem.family.lowercase().replace(" ", "_")}_variable.ttf")
                            Files.copy(downloaded.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            successCount++
                        } catch (e: IOException) {
                            failCount++
                        }
                    } else failCount++
                } else failCount++
            }

            // 2. Download Static Variants
            checkedVariants.forEach { variant ->
                val staticUrl = selectedItem.files?.get(variant)
                if (staticUrl != null) {
                    val downloaded = FontCacheManager.downloadFontFile(staticUrl, selectedItem.family, variant)
                    if (downloaded != null) {
                        try {
                            val destName = "${normalizeFontFilename(selectedItem.family, variant)}.ttf"
                            val dest = File(targetDir, destName)
                            Files.copy(downloaded.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            successCount++
                        } catch (e: IOException) {
                            failCount++
                        }
                    } else failCount++
                } else failCount++
            }

            SwingUtilities.invokeLater {
                statusLabel.text = ""
                // Refresh local file system
                val virtualFolder = VfsUtil.findFileByIoFile(targetDir, true)
                if (virtualFolder != null) {
                    VfsUtil.markDirtyAndRefresh(true, true, true, virtualFolder)
                }

                if (successCount > 0) {
                    val licenseType = licenseMap[selectedItem.family.lowercase()] ?: "SIL Open Font License 1.1 (OFL)"
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

        val helpLabel = JBLabel("<html>${MyBundle.message("font.setup.apikey.help")}</html>").apply {
            foreground = UIUtil.getLabelDisabledForeground()
            font = JBUI.Fonts.smallFont()
        }
        gbc.gridy = 2
        panel.add(helpLabel, gbc)

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
