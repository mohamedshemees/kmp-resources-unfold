package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JLabel

class FilterChip(
    val filterName: String,
    var isSelected: Boolean = false,
    var count: Int = 0,
    val onClick: (FilterChip) -> Unit
) : JLabel() {

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(4, 12, 4, 12)
        font = font.deriveFont(Font.BOLD, 11f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        updateAppearance()

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onClick(this@FilterChip)
            }
        })
    }

    fun setSelectedState(selected: Boolean, newCount: Int = count) {
        isSelected = selected
        count = newCount
        updateAppearance()
        repaint()
    }

    fun updateCount(newCount: Int) {
        count = newCount
        if (isSelected) {
            updateAppearance()
            repaint()
        }
    }

    private fun updateAppearance() {
        text = buildString {
            if (isSelected) append("✓ ")
            append(filterName)
            if (isSelected) append(" ($count)")
        }
        foreground = if (isSelected) JBColor.WHITE else JBColor.foreground()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = if (isSelected) JBColor.namedColor("Button.default.startBackground", JBColor(0xDC78B6, 0xDC78B6)) else JBColor.border()
        g2.fillRoundRect(0, 0, width, height, 16, 16)
        super.paintComponent(g)
        g2.dispose()
    }
}
