package com.github.mohamedshemees.kmpresourcesunfold.navigation

import com.github.mohamedshemees.kmpresourcesunfold.ResourceConstants
import com.github.mohamedshemees.kmpresourcesunfold.StringResourceProcessor
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext
import javax.swing.Icon

class ResourceKeyReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlAttributeValue::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    val attributeValue = element as XmlAttributeValue
                    val attribute = attributeValue.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
                    
                    if (attribute.name != "name") return PsiReference.EMPTY_ARRAY
                    val tag = attribute.parent as? XmlTag ?: return PsiReference.EMPTY_ARRAY
                    if (tag.name != "string") return PsiReference.EMPTY_ARRAY

                    val vFile = element.containingFile.virtualFile ?: return PsiReference.EMPTY_ARRAY
                    if (!vFile.path.contains(ResourceConstants.COMPOSE_RESOURCES_DIR)) return PsiReference.EMPTY_ARRAY

                    return arrayOf(ResourceKeyReference(attributeValue))
                }
            })
    }
}

class ResourceKeyReference(element: XmlAttributeValue) :
    PsiPolyVariantReferenceBase<XmlAttributeValue>(element, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val key = element.value
        if (key.isEmpty()) return ResolveResult.EMPTY_ARRAY

        val currentFile = element.containingFile.virtualFile ?: return ResolveResult.EMPTY_ARRAY
        val relatedFiles = StringResourceProcessor.findRelatedLocalizedFiles(currentFile)

        if (relatedFiles.isEmpty()) return ResolveResult.EMPTY_ARRAY

        val results = mutableListOf<ResolveResult>()
        val psiManager = PsiManager.getInstance(element.project)

        for (file in relatedFiles) {
            val psiFile = psiManager.findFile(file) as? XmlFile ?: continue
            val rootTag = psiFile.rootTag ?: continue
            
            val matchingTag = rootTag.findSubTags("string").find { 
                it.getAttributeValue("name") == key 
            }
            
            matchingTag?.let { tag ->
                val locale = file.parent.name.substringAfter("-", "default")
                results.add(PsiElementResolveResult(LocalizedXmlTag(tag, locale))) 
            }
        }

        return results.toTypedArray()
    }

    override fun getRangeInElement(): TextRange {
        val text = element.text
        if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
            return TextRange(1, text.length - 1)
        }
        return TextRange.EMPTY_RANGE
    }

    private class LocalizedXmlTag(private val delegate: XmlTag, private val locale: String) : XmlTag by delegate {

        override fun getName(): String = "${delegate.getAttributeValue("name")} [$locale]"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other is LocalizedXmlTag) return delegate == other.delegate
            return delegate == other
        }

        override fun hashCode(): Int = delegate.hashCode()
    }
}
