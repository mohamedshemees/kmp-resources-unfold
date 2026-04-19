package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.android.ide.common.vectordrawable.VdPreview
import com.github.mohamedshemees.kmpresourcesunfold.MyBundle
import com.github.mohamedshemees.kmpresourcesunfold.ResourceConstants
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagLayout
import java.beans.PropertyChangeListener
import javax.swing.*

class VectorPreviewEditor(private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val mainPanel = JPanel(BorderLayout())
    private val imageLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
    }

    private val canvasPanel = JPanel(GridBagLayout()).apply {
        background = UIUtil.getPanelBackground()
    }

    private var zoomScale = 1.0

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 2)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        }

        val zoomLabel = JBLabel("100%").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
        }

        fun createIconBtn(icon: Icon, tooltip: String, action: () -> Unit) = JButton(icon).apply {
            toolTipText = tooltip
            putClientProperty("JButton.buttonType", "square")
            isFocusable = false
            margin = JBUI.emptyInsets()
            isBorderPainted = false
            isContentAreaFilled = false
            addActionListener { action() }
        }

        val btnZoomOut = createIconBtn(AllIcons.General.ZoomOut, MyBundle.message("tooltip.zoomOut")) {
            zoomScale = (zoomScale - 0.2).coerceAtLeast(0.1)
            updateZoom(zoomLabel)
        }

        val btnReset = createIconBtn(AllIcons.General.ActualZoom, MyBundle.message("tooltip.resetZoom")) {
            zoomScale = 1.0
            updateZoom(zoomLabel)
        }

        val btnZoomIn = createIconBtn(AllIcons.General.ZoomIn, MyBundle.message("tooltip.zoomIn")) {
            zoomScale = (zoomScale + 0.2).coerceAtMost(15.0)
            updateZoom(zoomLabel)
        }

        toolbar.add(btnZoomOut)
        toolbar.add(btnReset)
        toolbar.add(btnZoomIn)
        toolbar.add(Box.createHorizontalStrut(5))
        toolbar.add(zoomLabel)

        canvasPanel.add(imageLabel)
        val scrollPane = JBScrollPane(canvasPanel).apply {
            border = JBUI.Borders.empty()
            viewport.background = UIUtil.getPanelBackground()
        }

        mainPanel.add(toolbar, BorderLayout.NORTH)
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        val document = FileDocumentManager.getInstance().getDocument(file)
        document?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                refreshPreview()
            }
        }, this)

        refreshPreview()
    }

    private fun updateZoom(label: JLabel) {
        label.text = "${(zoomScale * 100).toInt()}%"
        refreshPreview()
    }

    private fun refreshPreview() {
        val document = FileDocumentManager.getInstance().getDocument(file)
        val xmlContent = document?.text ?: String(file.contentsToByteArray(), Charsets.UTF_8)

        try {
            if (xmlContent.contains(ResourceConstants.VECTOR_TAG)) {
                val renderScale = 10.0 * zoomScale
                val targetSize = VdPreview.TargetSize.createFromScale(renderScale)
                val img = VdPreview.getPreviewFromVectorXml(targetSize, xmlContent, StringBuilder())

                if (img != null) {
                    imageLabel.icon = ImageIcon(img)
                    imageLabel.revalidate()
                    canvasPanel.revalidate()
                }
            }
        } catch (_: Throwable) {
        }
    }


    override fun getComponent(): JComponent = mainPanel
    override fun getPreferredFocusedComponent(): JComponent? = null
    override fun getName(): String = MyBundle.message("editor.design")
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(l: PropertyChangeListener) {}
    override fun removePropertyChangeListener(l: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun dispose() {}
}
