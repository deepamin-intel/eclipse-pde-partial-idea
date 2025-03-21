package cn.varsa.idea.pde.partial.plugin.facet

import cn.varsa.idea.pde.partial.common.*
import com.intellij.facet.*
import com.intellij.facet.ui.*
import com.intellij.openapi.components.*
import com.intellij.openapi.util.*
import com.intellij.util.xmlb.*
import com.intellij.util.xmlb.annotations.*
import java.util.concurrent.atomic.*

class PDEFacetConfiguration : FacetConfiguration, ModificationTracker, PersistentStateComponent<PDEFacetConfiguration> {
    private val modificationCount = AtomicLong()

    @Attribute var compilerOutputDirectory = "out"
    @XCollection(elementName = "binary", style = XCollection.Style.v2) val binaryOutput = hashSetOf(MetaInf)

    override fun createEditorTabs(
        editorContext: FacetEditorContext, validatorsManager: FacetValidatorsManager
    ): Array<FacetEditorTab> = arrayOf(PDEFacetEditorTab(this, editorContext, validatorsManager))

    override fun getModificationCount(): Long = modificationCount.get()
    override fun getState(): PDEFacetConfiguration = this
    override fun loadState(state: PDEFacetConfiguration) {
        XmlSerializerUtil.copyBean(state, this)
    }
}
