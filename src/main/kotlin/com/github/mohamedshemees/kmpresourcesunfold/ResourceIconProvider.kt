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
                val ext = ResourceExtension.fromExtension(file.extension)
                if (ext != null && ResourceExtension.fontExtensions.contains(ext)) {
                    FontFileIcon(file, size)
                } else {
                    val baseIcon = createIcon(file, size) ?: UIManager.getIcon("FileView.fileIcon")
                    CheckerboardIcon(baseIcon, size, size)
                }
            } catch (e: Throwable) {
                CheckerboardIcon(UIManager.getIcon("FileView.fileIcon"), size, size)
            }
        }
    }

    private fun drawCheckerboard(g2: Graphics2D, width: Int, height: Int, x: Int = 0, y: Int = 0) {
        val squareSize = if (width <= 32) 4 else 8
        val light = java.awt.Color(0x414243)
        val dark = java.awt.Color(0x393A3B)

        for (i in 0 until width step squareSize) {
            for (j in 0 until height step squareSize) {
                g2.color = if ((i / squareSize + j / squareSize) % 2 == 0) light else dark
                g2.fillRect(x + i, y + j, minOf(squareSize, width - i), minOf(squareSize, height - j))
            }
        }
    }

    class CheckerboardIcon(private val icon: Icon, private val width: Int, private val height: Int) : Icon {

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                drawCheckerboard(g2, width, height, x, y)
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

            ResourceExtension.TTF, ResourceExtension.OTF, ResourceExtension.WOFF, ResourceExtension.WOFF2 -> null
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
                ResourceExtension.TTF, ResourceExtension.OTF, ResourceExtension.WOFF, ResourceExtension.WOFF2 -> null
            } ?: return null

            val scale = if (bufferedImage.width > size || bufferedImage.height > size) {
                size.toDouble() / maxOf(bufferedImage.width, bufferedImage.height)
            } else 1.0

            val w = (bufferedImage.width * scale).toInt()
            val h = (bufferedImage.height * scale).toInt()
            
            val res = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = res.createGraphics()
            try {
                drawCheckerboard(g, w, h)
                g.drawImage(bufferedImage, 0, 0, w, h, null)
            } finally {
                g.dispose()
            }

            val baos = java.io.ByteArrayOutputStream()
            ImageIO.write(res, "png", baos)
            val base64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
            return "<img src=\"data:image/png;base64,$base64\" width=\"$w\" height=\"$h\">"
        } catch (e: Exception) {
            return null
        }
    }

    class FontFileIcon(private val file: VirtualFile, private val size: Int) : Icon {
        private var loadedFont: java.awt.Font? = null
        private var isFontLoaded = false

        private fun getFontToUse(c: Component?): java.awt.Font {
            if (!isFontLoaded) {
                isFontLoaded = true
                try {
                    loadedFont = file.inputStream.use { stream ->
                        java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, stream).deriveFont(java.awt.Font.BOLD, (size * 0.7f))
                    }
                } catch (e: Exception) {
                    // Ignore, fallback to system
                }
            }
            return loadedFont ?: c?.font?.deriveFont(java.awt.Font.BOLD, (size * 0.7f)) ?: java.awt.Font("Serif", java.awt.Font.BOLD, (size * 0.7f).toInt())
        }

        override fun paintIcon(c: Component?, g: java.awt.Graphics, x: Int, y: Int) {
            val g2d = g.create() as Graphics2D
            try {
                g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)

                val arc = (size * 0.25).toInt()
                g2d.color = JBColor(java.awt.Color(0xEAEAEA), java.awt.Color(0x3B3F42))
                g2d.fillRoundRect(x + 1, y + 1, size - 2, size - 2, arc, arc)
                
                g2d.color = JBColor(java.awt.Color(0xD0D0D0), java.awt.Color(0x2A2C2E))
                g2d.drawRoundRect(x + 1, y + 1, size - 2, size - 2, arc, arc)

                val fontToUse = getFontToUse(c)
                g2d.font = fontToUse
                g2d.color = JBColor(java.awt.Color(0x569CD6), java.awt.Color(0x569CD6))

                val text = "F"
                val fm = g2d.fontMetrics
                val textWidth = fm.stringWidth(text)
                val textHeight = fm.ascent
                
                val textX = x + (size - textWidth) / 2
                val textY = y + (size + textHeight) / 2 - 2
                g2d.drawString(text, textX, textY)
            } finally {
                g2d.dispose()
            }
        }

        override fun getIconWidth(): Int = size
        override fun getIconHeight(): Int = size
    }
}
