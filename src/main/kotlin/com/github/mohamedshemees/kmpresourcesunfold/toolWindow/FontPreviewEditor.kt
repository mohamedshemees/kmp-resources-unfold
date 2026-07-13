package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.ResourceUtils
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class FontPreviewEditor(private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val mainPanel = JPanel(BorderLayout())
    private var baseFont: Font? = null
    private var currentFontSize = 32f
    private var currentFontStyle = Font.PLAIN
    private var customText = "Grumpy wizards make toxic brew for the evil Queen."
    private var currentLineHeight = 1.2f
    private var currentLetterSpacing = 0.0f

    private val customPreviewPanel = FontPreviewPanel()
    private val glyphGridPanel = JPanel()

    init {
        // Load the font from inputStream
        try {
            baseFont = file.inputStream.use { stream ->
                Font.createFont(Font.TRUETYPE_FONT, stream)
            }
        } catch (e: Exception) {
            baseFont = Font("SansSerif", Font.PLAIN, 24)
        }

        // Toolbar
        val toolbar = JPanel(GridBagLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        }
        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(5)
            fill = GridBagConstraints.HORIZONTAL
            gridy = 0
        }

        // Row 1: Size & Style & Text
        // Size Slider & Spinner
        gbc.gridx = 0
        gbc.weightx = 0.0
        toolbar.add(JLabel("Size:"), gbc)

        val sizeSlider = JSlider(8, 120, 32).apply {
            isOpaque = false
            preferredSize = Dimension(200, preferredSize.height)
        }
        val sizeSpinner = JSpinner(SpinnerNumberModel(32, 8, 120, 1)).apply {
            preferredSize = Dimension(80, preferredSize.height)
        }

        sizeSlider.addChangeListener {
            val valSlider = sizeSlider.value
            if (sizeSpinner.value != valSlider) {
                sizeSpinner.value = valSlider
            }
            currentFontSize = valSlider.toFloat()
            updatePreview()
        }
        sizeSpinner.addChangeListener {
            val valSpinner = sizeSpinner.value as Int
            if (sizeSlider.value != valSpinner) {
                sizeSlider.value = valSpinner
            }
            currentFontSize = valSpinner.toFloat()
            updatePreview()
        }

        gbc.gridx = 1
        gbc.weightx = 0.0
        toolbar.add(sizeSlider, gbc)

        gbc.gridx = 2
        gbc.weightx = 0.0
        toolbar.add(sizeSpinner, gbc)

        // Bold & Italic Checkboxes
        val boldCb = JCheckBox("Bold").apply { isOpaque = false }
        val italicCb = JCheckBox("Italic").apply { isOpaque = false }

        val updateStyles = {
            var style = Font.PLAIN
            if (boldCb.isSelected) style = style or Font.BOLD
            if (italicCb.isSelected) style = style or Font.ITALIC
            currentFontStyle = style
            updatePreview()
        }
        boldCb.addActionListener { updateStyles() }
        italicCb.addActionListener { updateStyles() }

        gbc.gridx = 3
        gbc.weightx = 0.0
        toolbar.add(boldCb, gbc)

        gbc.gridx = 4
        gbc.weightx = 0.0
        toolbar.add(italicCb, gbc)

        // Custom preview input
        gbc.gridx = 5
        gbc.weightx = 0.0
        toolbar.add(Box.createHorizontalStrut(10), gbc)

        gbc.gridx = 6
        gbc.weightx = 0.0
        toolbar.add(JLabel("Preview Text:"), gbc)

        val previewTextField = JBTextField(customText).apply {
            preferredSize = Dimension(250, preferredSize.height)
        }
        previewTextField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = update()
            override fun removeUpdate(e: DocumentEvent) = update()
            override fun changedUpdate(e: DocumentEvent) = update()
            fun update() {
                customText = previewTextField.text
                updatePreview()
            }
        })

        gbc.gridx = 7
        gbc.weightx = 1.0
        toolbar.add(previewTextField, gbc)

        // Row 2: Line Height & Letter Spacing
        val gbcRow2 = GridBagConstraints().apply {
            insets = JBUI.insets(5)
            fill = GridBagConstraints.HORIZONTAL
            gridy = 1
        }

        gbcRow2.gridx = 0
        gbcRow2.weightx = 0.0
        toolbar.add(JLabel("Line Height:"), gbcRow2)

        val lineHeightSlider = JSlider(8, 30, 12).apply {
            isOpaque = false
            preferredSize = Dimension(200, preferredSize.height)
        }
        val lineHeightSpinner = JSpinner(SpinnerNumberModel(1.20, 0.80, 3.00, 0.05)).apply {
            preferredSize = Dimension(80, preferredSize.height)
        }

        lineHeightSlider.addChangeListener {
            val valSlider = lineHeightSlider.value / 10f
            if (Math.abs((lineHeightSpinner.value as Double).toFloat() - valSlider) > 0.01f) {
                lineHeightSpinner.value = valSlider.toDouble()
            }
            currentLineHeight = valSlider
            updatePreview()
        }
        lineHeightSpinner.addChangeListener {
            val valSpinner = (lineHeightSpinner.value as Double).toFloat()
            val sliderTarget = (valSpinner * 10).toInt()
            if (lineHeightSlider.value != sliderTarget) {
                lineHeightSlider.value = sliderTarget
            }
            currentLineHeight = valSpinner
            updatePreview()
        }

        gbcRow2.gridx = 1
        gbcRow2.weightx = 0.0
        toolbar.add(lineHeightSlider, gbcRow2)

        gbcRow2.gridx = 2
        gbcRow2.weightx = 0.0
        toolbar.add(lineHeightSpinner, gbcRow2)

        gbcRow2.gridx = 3
        gbcRow2.weightx = 0.0
        toolbar.add(JLabel("Letter Spacing:"), gbcRow2)

        val letterSpacingSlider = JSlider(-10, 50, 0).apply {
            isOpaque = false
            preferredSize = Dimension(150, preferredSize.height)
        }
        val letterSpacingSpinner = JSpinner(SpinnerNumberModel(0.00, -0.10, 0.50, 0.01)).apply {
            preferredSize = Dimension(80, preferredSize.height)
        }

        letterSpacingSlider.addChangeListener {
            val valSlider = letterSpacingSlider.value / 100f
            if (Math.abs((letterSpacingSpinner.value as Double).toFloat() - valSlider) > 0.001f) {
                letterSpacingSpinner.value = valSlider.toDouble()
            }
            currentLetterSpacing = valSlider
            updatePreview()
        }
        letterSpacingSpinner.addChangeListener {
            val valSpinner = (letterSpacingSpinner.value as Double).toFloat()
            val sliderTarget = (valSpinner * 100).toInt()
            if (letterSpacingSlider.value != sliderTarget) {
                letterSpacingSlider.value = sliderTarget
            }
            currentLetterSpacing = valSpinner
            updatePreview()
        }

        gbcRow2.gridx = 4
        gbcRow2.weightx = 0.0
        toolbar.add(letterSpacingSlider, gbcRow2)

        gbcRow2.gridx = 5
        gbcRow2.weightx = 0.0
        toolbar.add(letterSpacingSpinner, gbcRow2)

        // Content Scroll pane
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(20)
        }

        // Custom Specimen Panel
        val customSection = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Interactive Preview")
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        customPreviewPanel.apply {
            border = JBUI.Borders.empty(15)
            foreground = JBColor.foreground()
            background = UIUtil.getPanelBackground()
        }
        customSection.add(customPreviewPanel, BorderLayout.CENTER)
        contentPanel.add(customSection)
        contentPanel.add(Box.createVerticalStrut(15))

        // Waterfall Preview Section
        val waterfallSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createTitledBorder("Waterfall Preview")
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val waterfallSizes = listOf(12, 16, 20, 24, 36, 48)
        waterfallSizes.forEach { size ->
            val sizeLabel = JLabel("$size pt:").apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getLabelDisabledForeground()
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val textLabel = AntiAliasedLabel("The quick brown fox jumps over the lazy dog. 1234567890").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(2, 0, 8, 0)
                foreground = JBColor.foreground()
            }
            textLabel.font = baseFont?.deriveFont(Font.PLAIN, size.toFloat()) ?: Font("SansSerif", Font.PLAIN, size)
            waterfallSection.add(sizeLabel)
            waterfallSection.add(textLabel)
        }
        contentPanel.add(waterfallSection)
        contentPanel.add(Box.createVerticalStrut(15))

        // Character Map Panel
        val glyphSection = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Character Map")
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        glyphGridPanel.apply {
            layout = GridLayout(0, 12, 6, 6)
            isOpaque = false
            border = JBUI.Borders.empty(10)
        }

        val glyphFont = baseFont?.deriveFont(18f) ?: Font("SansSerif", Font.PLAIN, 18)
        for (charValue in 32..126) {
            val char = charValue.toChar()
            val card = JPanel(BorderLayout()).apply {
                background = UIUtil.getPanelBackground()
                border = BorderFactory.createLineBorder(JBColor.border())
                preferredSize = Dimension(45, 55)
                
                val glyphLabel = AntiAliasedLabel(char.toString()).apply {
                    horizontalAlignment = SwingConstants.CENTER
                    font = glyphFont
                    foreground = JBColor.foreground()
                }
                val hexLabel = JLabel("U+${String.format("%04X", charValue)}", SwingConstants.CENTER).apply {
                    font = JBUI.Fonts.miniFont()
                    foreground = UIUtil.getLabelDisabledForeground()
                }
                add(glyphLabel, BorderLayout.CENTER)
                add(hexLabel, BorderLayout.SOUTH)
            }
            glyphGridPanel.add(card)
        }
        glyphSection.add(glyphGridPanel, BorderLayout.CENTER)
        contentPanel.add(glyphSection)

        val scrollPane = JBScrollPane(contentPanel).apply {
            border = JBUI.Borders.empty()
            viewport.background = UIUtil.getPanelBackground()
        }

        // Footer details
        val metadataPanel = JPanel(GridBagLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
        }
        val mGbc = GridBagConstraints().apply {
            insets = JBUI.insets(8, 12)
            anchor = GridBagConstraints.WEST
            gridy = 0
        }

        fun addMetaField(label: String, valText: String, x: Int): JLabel {
            mGbc.gridx = x
            val valLabel = JLabel(valText).apply {
                font = JBUI.Fonts.label()
                foreground = JBColor.foreground()
            }
            val field = JPanel(BorderLayout(5, 0)).apply {
                isOpaque = false
                add(JLabel("$label:").apply {
                    font = JBUI.Fonts.label()
                    foreground = UIUtil.getLabelDisabledForeground()
                }, BorderLayout.WEST)
                add(valLabel, BorderLayout.CENTER)
            }
            metadataPanel.add(field, mGbc)
            return valLabel
        }

        val fontName = baseFont?.fontName ?: file.nameWithoutExtension
        val fontFamily = baseFont?.family ?: "Unknown"
        val sizeStr = ResourceUtils.formatSize(file.length)
        val extStr = file.extension?.uppercase() ?: "TTF"

        addMetaField("Font Name", fontName, 0)
        addMetaField("Family Name", fontFamily, 1)
        addMetaField("Format", extStr, 2)
        addMetaField("File Size", sizeStr, 3)

        // Parse License from name table
        val fullLicense = try {
            file.inputStream.use { stream ->
                TtfMetadataParser.parseLicenseFromBytes(stream.readBytes())
            }
        } catch (e: Exception) { null }

        val cleanLicense = if (fullLicense != null) {
            when {
                fullLicense.contains("SIL Open Font License", ignoreCase = true) -> "SIL Open Font License 1.1 (OFL)"
                fullLicense.contains("Apache License", ignoreCase = true) -> "Apache License 2.0"
                fullLicense.contains("MIT License", ignoreCase = true) -> "MIT License"
                else -> {
                    if (fullLicense.length > 30) fullLicense.substring(0, 27) + "..." else fullLicense
                }
            }
        } else "Unknown / Proprietary"

        val licenseLabel = addMetaField("License", cleanLicense, 4)
        if (fullLicense != null) {
            val formattedTooltip = "<html><body>" + fullLicense.replace("\n", "<br/>") + "</body></html>"
            licenseLabel.toolTipText = formattedTooltip
        }

        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        mainPanel.add(metadataPanel, BorderLayout.SOUTH)

        updatePreview()
    }

    private fun updatePreview() {
        val derived = baseFont?.deriveFont(currentFontStyle, currentFontSize) ?: Font("SansSerif", currentFontStyle, currentFontSize.toInt())
        customPreviewPanel.previewFont = derived
        customPreviewPanel.previewText = customText
        customPreviewPanel.tracking = currentLetterSpacing
        customPreviewPanel.lineHeightMultiplier = currentLineHeight
        customPreviewPanel.revalidate()
        customPreviewPanel.repaint()
    }

    override fun getComponent(): JComponent = mainPanel
    override fun getPreferredFocusedComponent(): JComponent? = null
    override fun getName(): String = "Font Preview"
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(l: PropertyChangeListener) {}
    override fun removePropertyChangeListener(l: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun dispose() {}
}

private class AntiAliasedLabel(text: String = "") : JLabel(text) {
    override fun paintComponent(g: Graphics) {
        val g2d = g as? Graphics2D
        if (g2d != null) {
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        }
        super.paintComponent(g)
    }
}

private class FontPreviewPanel : JPanel() {
    var previewText = ""
    var previewFont: Font? = null
    var tracking = 0f
    var lineHeightMultiplier = 1.2f

    private fun wrapText(text: String, fontMetrics: FontMetrics, maxWidth: Int): List<String> {
        if (maxWidth <= 50) return listOf(text)
        val wrappedLines = mutableListOf<String>()
        val paragraphs = text.split("\n")
        for (paragraph in paragraphs) {
            val words = paragraph.split(" ")
            var currentLine = StringBuilder()
            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
                val testWidth = fontMetrics.stringWidth(testLine)
                if (testWidth > maxWidth) {
                    if (currentLine.isNotEmpty()) {
                        wrappedLines.add(currentLine.toString())
                        currentLine = StringBuilder(word)
                    } else {
                        wrappedLines.add(word)
                    }
                } else {
                    currentLine = StringBuilder(testLine)
                }
            }
            if (currentLine.isNotEmpty()) {
                wrappedLines.add(currentLine.toString())
            }
        }
        return wrappedLines
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g.create() as? Graphics2D ?: return
        try {
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val baseFont = previewFont ?: return
            
            val attributes = mutableMapOf<java.awt.font.TextAttribute, Any>()
            attributes[java.awt.font.TextAttribute.TRACKING] = tracking
            val fontToUse = baseFont.deriveFont(attributes)
            g2d.font = fontToUse
            g2d.color = foreground

            val fm = g2d.fontMetrics
            val x = 15
            var y = 15 + fm.ascent

            val maxWidth = maxOf(width - 30, 200)
            val lines = wrapText(previewText, fm, maxWidth)
            val lineHeight = (fm.height * lineHeightMultiplier).toInt()

            lines.forEach { line ->
                g2d.drawString(line, x, y)
                y += lineHeight
            }
        } finally {
            g2d.dispose()
        }
    }

    override fun getPreferredSize(): Dimension {
        val parentWidth = parent?.width ?: 600
        val maxWidth = maxOf(parentWidth - 30, 200)
        
        val fm = getFontMetrics(previewFont ?: font)
        val lines = wrapText(previewText, fm, maxWidth)
        val h = lines.size * (fm.height * lineHeightMultiplier).toInt() + 30
        return Dimension(maxWidth, maxOf(h, 150))
    }
}

private object TtfMetadataParser {
    fun parseLicenseFromBytes(data: ByteArray): String? {
        try {
            val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.BIG_ENDIAN)
            val sfntVersion = buffer.int
            val numTables = buffer.short.toInt()
            val searchRange = buffer.short
            val entrySelector = buffer.short
            val rangeShift = buffer.short

            var nameTableOffset = -1
            var nameTableLength = -1
            for (i in 0 until numTables) {
                val tagBytes = ByteArray(4)
                buffer.get(tagBytes)
                val tag = String(tagBytes, Charsets.US_ASCII)
                val checksum = buffer.int
                val offset = buffer.int
                val length = buffer.int

                if (tag == "name") {
                    nameTableOffset = offset
                    nameTableLength = length
                    break
                }
            }

            if (nameTableOffset == -1) return null

            buffer.position(nameTableOffset)
            val format = buffer.short.toInt()
            val count = buffer.short.toInt()
            val stringOffset = buffer.short.toInt()

            val stringBase = nameTableOffset + stringOffset
            var licenseDesc: String? = null
            var copyright: String? = null

            for (i in 0 until count) {
                val platformID = buffer.short.toInt()
                val encodingID = buffer.short.toInt()
                val languageID = buffer.short.toInt()
                val nameID = buffer.short.toInt()
                val length = buffer.short.toInt()
                val offset = buffer.short.toInt()

                if (nameID == 13 || nameID == 0) {
                    val prevPos = buffer.position()
                    buffer.position(stringBase + offset)
                    val stringBytes = ByteArray(length)
                    buffer.get(stringBytes)
                    buffer.position(prevPos)

                    val decoded = if (platformID == 3 || platformID == 0) {
                        String(stringBytes, Charsets.UTF_16BE)
                    } else {
                        String(stringBytes, Charsets.US_ASCII)
                    }.trim()

                    if (decoded.isNotEmpty()) {
                        if (nameID == 13) {
                            licenseDesc = decoded
                        } else if (nameID == 0) {
                            copyright = decoded
                        }
                    }
                }
            }

            return licenseDesc ?: copyright
        } catch (e: Exception) {
            return null
        }
    }
}
