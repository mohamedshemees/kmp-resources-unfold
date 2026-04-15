package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.android.ide.common.vectordrawable.VdPreview
import com.github.mohamedshemees.kmpresourcesunfold.ResourceConstants
import com.github.mohamedshemees.kmpresourcesunfold.ResourceExtension
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.SVGLoader
import java.awt.*
import javax.imageio.ImageIO
import javax.swing.*

@Suppress("UnstableApiUsage")
object ResourceIconProvider {
    private val iconCache = mutableMapOf<String, Icon>()
    private const val VECTOR_HARD_SIZE = 64
    private const val IMAGE_THUMBNAIL_SIZE = 128.0

    fun getIcon(file: VirtualFile): Icon {
        return iconCache.getOrPut(file.path) {
            try {
                val ext = ResourceExtension.fromExtension(file.extension)
                var finalIcon: Icon? = null

                when (ext) {
                    ResourceExtension.PNG, ResourceExtension.JPG, ResourceExtension.JPEG, ResourceExtension.WEBP -> {
                        file.inputStream.use { stream ->
                            val img = ImageIO.read(stream)
                            if (img != null) {
                                val scale = minOf(IMAGE_THUMBNAIL_SIZE / img.width, IMAGE_THUMBNAIL_SIZE / img.height)
                                val scaled = img.getScaledInstance(
                                    (img.width * scale).toInt(),
                                    (img.height * scale).toInt(),
                                    Image.SCALE_SMOOTH
                                )
                                finalIcon = ImageIcon(scaled)
                            }
                        }
                    }

                    ResourceExtension.SVG -> {
                        file.inputStream.use { stream ->
                            val url = java.net.URL("file://${file.path}")
                            val img = SVGLoader.load(url, stream, 5.0f)
                            finalIcon = ImageIcon(
                                img.getScaledInstance(VECTOR_HARD_SIZE, VECTOR_HARD_SIZE, Image.SCALE_SMOOTH)
                            )
                        }
                    }

                    ResourceExtension.XML -> {
                        val xmlContent = String(file.contentsToByteArray(), Charsets.UTF_8)
                        if (xmlContent.contains(ResourceConstants.VECTOR_TAG)) {
                            val img = VdPreview.getPreviewFromVectorXml(
                                VdPreview.TargetSize.createFromScale(2.0),
                                xmlContent,
                                StringBuilder()
                            )
                            if (img != null) finalIcon = ImageIcon(
                                img.getScaledInstance(VECTOR_HARD_SIZE, VECTOR_HARD_SIZE, Image.SCALE_SMOOTH)
                            )
                        }
                    }

                    else -> {}
                }

                finalIcon ?: UIManager.getIcon("FileView.fileIcon")
            } catch (e: Throwable) {
                UIManager.getIcon("FileView.fileIcon")
            }
        }
    }
}

class ResourceListCellRenderer : ListCellRenderer<VirtualFile> {
    override fun getListCellRendererComponent(
        list: JList<out VirtualFile>,
        file: VirtualFile?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val label = JLabel(file?.name ?: "")
        val panel = object : JPanel(BorderLayout()) {
            override fun getAccessibleContext() = label.accessibleContext
        }

        label.apply {
            iconTextGap = 20
            horizontalAlignment = JLabel.LEFT
            foreground = list.foreground
            icon = file?.let { ResourceIconProvider.getIcon(it) } ?: UIManager.getIcon("FileView.fileIcon")
        }

        panel.apply {
            isOpaque = true
            background = list.background
            add(label, BorderLayout.CENTER)

            val padding = BorderFactory.createEmptyBorder(8, 12, 8, 12)

            if (isSelected) {
                val selectionBorder = BorderFactory.createLineBorder(JBColor.namedColor("List.selectionInactiveBackground", JBColor.BLUE), 1)
                border = BorderFactory.createCompoundBorder(selectionBorder, padding)
            } else {
                val separator = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
                border = BorderFactory.createCompoundBorder(separator, padding)
            }
        }

        return panel
    }
}

