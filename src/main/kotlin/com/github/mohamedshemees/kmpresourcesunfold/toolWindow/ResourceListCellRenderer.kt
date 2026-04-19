package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.android.ide.common.vectordrawable.VdPreview
import com.github.mohamedshemees.kmpresourcesunfold.MyBundle
import com.github.mohamedshemees.kmpresourcesunfold.ResourceConstants
import com.github.mohamedshemees.kmpresourcesunfold.ResourceExtension
import com.github.mohamedshemees.kmpresourcesunfold.StringResource
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ImageLoader
import com.intellij.util.ui.ImageUtil
import java.awt.*
import javax.imageio.ImageIO
import javax.swing.*


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
                                val scaled = ImageUtil.toBufferedImage(img.getScaledInstance(
                                    (img.width * scale).toInt(),
                                    (img.height * scale).toInt(),
                                    Image.SCALE_SMOOTH
                                ))
                                finalIcon = ImageIcon(scaled)
                            }
                        }
                    }

                    ResourceExtension.SVG -> {
                        val img = ImageLoader.loadFromUrl(java.net.URI.create(file.url).toURL())
                        if (img != null) {
                            finalIcon = ImageIcon(
                                ImageUtil.toBufferedImage(img.getScaledInstance(VECTOR_HARD_SIZE, VECTOR_HARD_SIZE, Image.SCALE_SMOOTH))
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

class ResourceListCellRenderer : ListCellRenderer<Any> {
    override fun getListCellRendererComponent(
        list: JList<out Any>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val panel = JPanel(BorderLayout())
        val label = JLabel()
        val infoLabel = JLabel()

        when (value) {
            is VirtualFile -> {
                label.text = value.name
                label.icon = ResourceIconProvider.getIcon(value)
            }
            is StringResource -> {
                label.text = value.key
                label.icon = UIManager.getIcon("FileView.fileIcon")
                
                if (value.missingLocales.isNotEmpty()) {
                    infoLabel.text = MyBundle.message("message.missingTranslations", value.missingLocales.joinToString(", "))
                    infoLabel.foreground = JBColor.RED
                } else {
                    infoLabel.text = MyBundle.message("message.fullyTranslated")
                    infoLabel.foreground = JBColor.GREEN
                }
                infoLabel.font = infoLabel.font.deriveFont(10f)
            }
        }

        label.apply {
            iconTextGap = 20
            horizontalAlignment = JLabel.LEFT
            foreground = list.foreground
        }

        panel.apply {
            isOpaque = true
            background = list.background
            add(label, BorderLayout.CENTER)
            add(infoLabel, BorderLayout.EAST)

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


