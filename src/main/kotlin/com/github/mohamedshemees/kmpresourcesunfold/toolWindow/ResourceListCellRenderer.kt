package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.*
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.*
import javax.swing.*

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
