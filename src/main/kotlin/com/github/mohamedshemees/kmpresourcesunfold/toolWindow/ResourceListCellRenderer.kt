package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.*
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.*
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
                label.font = label.font.deriveFont(Font.BOLD, 13f)
                panel.background = list.background
                val padding = BorderFactory.createEmptyBorder(10, 12, 5, 12)
                panel.border = padding
                panel.add(label, BorderLayout.CENTER)
                return panel
            }
            is ResourceChild -> {
                label.text = value.density
                label.foreground = JBColor.GRAY
                label.font = label.font.deriveFont(11f)
                
                val moduleName = (ModuleUtilCore.findModuleForFile(value.file, project)?.name ?: "Unknown").let {
                    with(ResourceUtils) { it.cleanModuleName() }
                }
                subLabel.text = moduleName
                subLabel.foreground = JBColor.LIGHT_GRAY
                subLabel.font = subLabel.font.deriveFont(9f)
                
                val indentPanel = JPanel(BorderLayout(10, 0))
                indentPanel.isOpaque = false
                indentPanel.border = BorderFactory.createEmptyBorder(0, 35, 0, 0)
                indentPanel.add(label, BorderLayout.WEST)
                indentPanel.add(subLabel, BorderLayout.CENTER)
                
                panel.add(indentPanel, BorderLayout.CENTER)
            }
            is StringResource -> {
                label.text = value.key
                label.icon = UIManager.getIcon("FileView.fileIcon")
                label.font = label.font.deriveFont(Font.BOLD, 12f)
                
                val moduleName = (ModuleUtilCore.findModuleForFile(value.file, project)?.name ?: "Unknown").let {
                    with(ResourceUtils) { it.cleanModuleName() }
                }
                subLabel.text = moduleName
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
            is VirtualFile -> {
                label.text = value.name
                label.icon = ResourceIconProvider.getIcon(value)
                label.font = label.font.deriveFont(Font.BOLD, 12f)

                
                val moduleName = (ModuleUtilCore.findModuleForFile(value, project)?.name ?: "Unknown").let {
                    with(ResourceUtils) { it.cleanModuleName() }
                }
                subLabel.text = moduleName
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
