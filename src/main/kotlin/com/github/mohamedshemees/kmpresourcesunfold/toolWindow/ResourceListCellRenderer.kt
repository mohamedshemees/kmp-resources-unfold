package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.MyBundle
import com.github.mohamedshemees.kmpresourcesunfold.ResourceIconProvider
import com.github.mohamedshemees.kmpresourcesunfold.ResourceUtils
import com.github.mohamedshemees.kmpresourcesunfold.StringResource
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.GridLayout
import javax.swing.*

class ResourceListCellRenderer(private val project: Project) : ListCellRenderer<Any> {
    override fun getListCellRendererComponent(
        list: JList<out Any>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val panel = JPanel(BorderLayout())
        val label = JLabel()
        val subLabel = JLabel()
        val infoLabel = JLabel()

        when (value) {
            is ResourceParent -> {
                label.text = value.name
                label.icon = value.icon
                label.iconTextGap = 8
                label.font = label.font.deriveFont(Font.BOLD, 12f)
                label.alignmentY = Component.CENTER_ALIGNMENT

                val contentPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    add(label)
                    if (value.subtitle.isNotEmpty()) {
                        add(Box.createHorizontalStrut(8))
                        subLabel.text = value.subtitle
                        subLabel.foreground = JBColor.GRAY
                        subLabel.font = subLabel.font.deriveFont(10f)
                        subLabel.alignmentY = Component.CENTER_ALIGNMENT
                        add(subLabel)
                    }
                }

                panel.background = list.background
                val padding = BorderFactory.createEmptyBorder(6, 12, 6, 12)
                panel.border = padding
                panel.add(contentPanel, BorderLayout.WEST)
            }
            is ResourceChild -> {
                label.text = value.density
                label.foreground = JBColor.GRAY
                label.font = label.font.deriveFont(11f)
                label.alignmentY = Component.CENTER_ALIGNMENT
                
                subLabel.text = if (value.file.extension?.lowercase() == "kt") "${value.moduleName} • ${value.file.name}" else value.moduleName
                subLabel.foreground = JBColor.LIGHT_GRAY
                subLabel.font = subLabel.font.deriveFont(9f)
                subLabel.alignmentY = Component.CENTER_ALIGNMENT
                
                val indentPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    border = BorderFactory.createEmptyBorder(0, 35, 0, 0)
                    add(label)
                    add(Box.createHorizontalStrut(10))
                    add(subLabel)
                }
                
                panel.add(indentPanel, BorderLayout.WEST)
            }
            is StringResource -> {
                label.text = value.key
                label.icon = UIManager.getIcon("FileView.fileIcon")
                label.font = label.font.deriveFont(Font.BOLD, 12f)
                
                subLabel.text = value.moduleName
                subLabel.foreground = JBColor.GRAY
                subLabel.font = subLabel.font.deriveFont(10f)
                
                val contentPanel = JPanel(GridLayout(2, 1, 0, 2))
                contentPanel.isOpaque = false
                contentPanel.border = BorderFactory.createEmptyBorder(0, 35, 0, 0)
                contentPanel.add(label)
                contentPanel.add(subLabel)
                
                if (value.missingLocales.isNotEmpty()) {
                    infoLabel.text = MyBundle.message("message.missingTranslations", value.missingLocales.joinToString(", "))
                    infoLabel.foreground = JBColor.RED
                } else {
                    infoLabel.text = MyBundle.message("message.fullyTranslated")
                    infoLabel.foreground = JBColor.GREEN
                }
                infoLabel.font = infoLabel.font.deriveFont(10f)
                
                panel.add(contentPanel, BorderLayout.CENTER)
                panel.add(infoLabel, BorderLayout.EAST)
            }
            is ResourceFileItem -> {
                label.text = value.file.name
                label.icon = ResourceIconProvider.getIcon(value.file)
                label.font = label.font.deriveFont(Font.BOLD, 12f)

                subLabel.text = value.moduleName
                subLabel.foreground = JBColor.GRAY
                subLabel.font = subLabel.font.deriveFont(10f)
                
                val contentPanel = JPanel(GridLayout(2, 1, 0, 2))
                contentPanel.isOpaque = false
                contentPanel.border = BorderFactory.createEmptyBorder(0, 35, 0, 0)
                contentPanel.add(label)
                contentPanel.add(subLabel)
                
                panel.add(contentPanel, BorderLayout.CENTER)
            }
        }

        panel.apply {
            isOpaque = true
            background = list.background
            toolTipText = null
            
            val padding = BorderFactory.createEmptyBorder(6, 12, 6, 12)
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
