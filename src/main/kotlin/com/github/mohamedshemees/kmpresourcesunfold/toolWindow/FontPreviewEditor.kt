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

    private val customPreviewLabel = AntiAliasedLabel()
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
        customPreviewLabel.apply {
            border = JBUI.Borders.empty(15)
            foreground = JBColor.foreground()
        }
        customSection.add(customPreviewLabel, BorderLayout.CENTER)
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

        fun addMetaField(label: String, valText: String, x: Int) {
            mGbc.gridx = x
            val field = JPanel(BorderLayout(5, 0)).apply {
                isOpaque = false
                add(JLabel("$label:").apply {
                    font = JBUI.Fonts.label()
                    foreground = UIUtil.getLabelDisabledForeground()
                }, BorderLayout.WEST)
                add(JLabel(valText).apply {
                    font = JBUI.Fonts.label()
                    foreground = JBColor.foreground()
                }, BorderLayout.CENTER)
            }
            metadataPanel.add(field, mGbc)
        }

        val fontName = baseFont?.fontName ?: file.nameWithoutExtension
        val fontFamily = baseFont?.family ?: "Unknown"
        val sizeStr = ResourceUtils.formatSize(file.length)
        val extStr = file.extension?.uppercase() ?: "TTF"

        addMetaField("Font Name", fontName, 0)
        addMetaField("Family Name", fontFamily, 1)
        addMetaField("Format", extStr, 2)
        addMetaField("File Size", sizeStr, 3)

        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        mainPanel.add(metadataPanel, BorderLayout.SOUTH)

        updatePreview()
    }

    private fun updatePreview() {
        val derived = baseFont?.deriveFont(currentFontStyle, currentFontSize) ?: Font("SansSerif", currentFontStyle, currentFontSize.toInt())
        customPreviewLabel.font = derived
        customPreviewLabel.text = customText
        customPreviewLabel.revalidate()
        customPreviewLabel.repaint()
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
