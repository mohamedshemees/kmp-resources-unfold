package com.github.mohamedshemees.kmpresourcesunfold

import com.android.ide.common.vectordrawable.VdPreview
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.UIManager

object ResourceIconProvider {
    private val iconCache = mutableMapOf<String, Icon>()
    private const val VECTOR_HARD_SIZE = 64

    fun clearCache() {
        iconCache.clear()
    }

    fun getIcon(file: VirtualFile, size: Int = VECTOR_HARD_SIZE): Icon {
        val cacheKey = "${file.path}_$size"
        return iconCache.getOrPut(cacheKey) {
            try {
                val baseIcon = createIcon(file, size) ?: UIManager.getIcon("FileView.fileIcon")
                CheckerboardIcon(baseIcon, size, size)
            } catch (e: Throwable) {
                CheckerboardIcon(UIManager.getIcon("FileView.fileIcon"), size, size)
            }
        }
    }

    class CheckerboardIcon(private val icon: Icon, private val width: Int, private val height: Int) : Icon {

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                // Adjust square size based on icon size: 4px for small icons, 8px for large ones
                val squareSize = if (width <= 32) 4 else 8
                val light = JBColor(0xFFFFFF, 0xFFFFFF)
                val dark = JBColor(0xC0C0C0, 0xC0C0C0)

                for (i in 0 until width step squareSize) {
                    for (j in 0 until height step squareSize) {
                        g2.color = if ((i / squareSize + j / squareSize) % 2 == 0) light else dark
                        g2.fillRect(x + i, y + j, minOf(squareSize, width - i), minOf(squareSize, height - j))
                    }
                }

                val iconX = x + (width - icon.iconWidth) / 2
                val iconY = y + (height - icon.iconHeight) / 2
                icon.paintIcon(c, g2, iconX, iconY)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = width
        override fun getIconHeight(): Int = height
    }

    private fun createIcon(file: VirtualFile, size: Int): Icon? {
        val ext = ResourceExtension.fromExtension(file.extension) ?: return null
        
        return when (ext) {
            ResourceExtension.PNG, ResourceExtension.JPG, ResourceExtension.JPEG, ResourceExtension.WEBP -> {
                file.inputStream.use { stream ->
                    val img = ImageIO.read(stream)
                    if (img != null) {
                        val maxDim = maxOf(img.width, img.height).toDouble()
                        val scale = if (maxDim > size) size / maxDim else 1.0
                        val scaled = img.getScaledInstance(
                            (img.width * scale).toInt(),
                            (img.height * scale).toInt(),
                            Image.SCALE_SMOOTH
                        )
                        ImageIcon(scaled)
                    } else null
                }
            }

            ResourceExtension.SVG -> {
                val xmlContent = SvgToXmlConverter.convertToXml(file.inputStream)
                renderVector(xmlContent, size)
            }

            ResourceExtension.XML -> {
                val xmlContent = String(file.contentsToByteArray(), Charsets.UTF_8)
                if (xmlContent.contains(ResourceConstants.VECTOR_TAG)) {
                    renderVector(xmlContent, size)
                } else null
            }
        }
    }

    private fun renderVector(xmlContent: String, size: Int): Icon? {
        val targetSize = VdPreview.TargetSize.createFromMaxDimension(size)
        val img = VdPreview.getPreviewFromVectorXml(targetSize, xmlContent, StringBuilder())
        return img?.let { ImageIcon(it) }
    }

    fun getIconAsHtml(file: VirtualFile, size: Int): String? {
        try {
            val ext = ResourceExtension.fromExtension(file.extension) ?: return null
            val bufferedImage = when (ext) {
                ResourceExtension.PNG, ResourceExtension.JPG, ResourceExtension.JPEG, ResourceExtension.WEBP -> {
                    file.inputStream.use { ImageIO.read(it) }
                }
                ResourceExtension.SVG -> {
                    val xmlContent = SvgToXmlConverter.convertToXml(file.inputStream)
                    val targetSize = VdPreview.TargetSize.createFromMaxDimension(size)
                    VdPreview.getPreviewFromVectorXml(targetSize, xmlContent, StringBuilder())
                }
                ResourceExtension.XML -> {
                    val xmlContent = String(file.contentsToByteArray(), Charsets.UTF_8)
                    if (xmlContent.contains(ResourceConstants.VECTOR_TAG)) {
                        val targetSize = VdPreview.TargetSize.createFromMaxDimension(size)
                        VdPreview.getPreviewFromVectorXml(targetSize, xmlContent, StringBuilder())
                    } else null
                }
            } ?: return null

            val scaled = if (bufferedImage.width > size || bufferedImage.height > size) {
                val scale = size.toDouble() / maxOf(bufferedImage.width, bufferedImage.height)
                val w = (bufferedImage.width * scale).toInt()
                val h = (bufferedImage.height * scale).toInt()
                val res = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val g = res.createGraphics()
                g.drawImage(bufferedImage, 0, 0, w, h, null)
                g.dispose()
                res
            } else bufferedImage

            val baos = java.io.ByteArrayOutputStream()
            ImageIO.write(scaled, "png", baos)
            val base64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
            return "<img src=\"data:image/png;base64,$base64\" width=\"${scaled.width}\" height=\"${scaled.height}\">"
        } catch (e: Exception) {
            return null
        }
    }
}
