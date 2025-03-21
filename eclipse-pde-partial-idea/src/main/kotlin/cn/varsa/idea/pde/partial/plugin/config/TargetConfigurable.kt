package cn.varsa.idea.pde.partial.plugin.config

import cn.varsa.idea.pde.partial.common.support.*
import cn.varsa.idea.pde.partial.plugin.domain.*
import cn.varsa.idea.pde.partial.plugin.i18n.EclipsePDEPartialBundles.message
import cn.varsa.idea.pde.partial.plugin.listener.*
import cn.varsa.idea.pde.partial.plugin.support.*
import com.intellij.icons.*
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileChooser.*
import com.intellij.openapi.options.*
import com.intellij.openapi.project.*
import com.intellij.openapi.roots.*
import com.intellij.openapi.ui.*
import com.intellij.ui.*
import com.intellij.ui.components.*
import com.intellij.ui.components.panels.*
import com.intellij.ui.speedSearch.*
import com.intellij.util.ui.*
import com.intellij.util.ui.components.*
import com.jetbrains.rd.swing.*
import com.jetbrains.rd.util.reactive.*
import java.awt.*
import java.awt.event.*
import java.util.*
import javax.swing.*
import javax.swing.tree.*

class TargetConfigurable(private val project: Project) : SearchableConfigurable, PanelWithAnchor {
    private val service by lazy { TargetDefinitionService.getInstance(project) }
    private var launcherAnchor: JComponent? = null
    private val locationModified = mutableSetOf<Pair<TargetLocationDefinition?, TargetLocationDefinition?>>()

    private val panel = JBTabbedPane()

    private val launcherJarCombo = ComboBox<String>()
    private val launcherCombo = ComboBox<String>()
    private val launcherJar =
        LabeledComponent.create(launcherJarCombo, message("config.target.launcherJar"), BorderLayout.WEST)
    private val launcher = LabeledComponent.create(launcherCombo, message("config.target.launcher"), BorderLayout.WEST)

    private val locationModel = DefaultListModel<TargetLocationDefinition>()
    private val locationList = JBList(locationModel).apply {
        setEmptyText(message("config.target.empty"))
        cellRenderer = ColoredListCellRendererWithSpeedSearch<TargetLocationDefinition> { value ->
            value?.also { location ->
                location.alias?.also {
                    append(it, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(": ")
                }
                append(location.location)
                append(
                    message("config.target.locationInfoInfix", location.bundles.size, location.dependency),
                    SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
                )
            }
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val selection = !isSelectionEmpty
                if (selection) editLocation()
                return selection
            }
        }.installOn(this)
        ListSpeedSearch(this).setClearSearchOnNavigateNoMatch(true)
    }

    private val startupModel = DefaultListModel<Pair<String, Int>>()
    private val startupList = JBList(startupModel).apply {
        setEmptyText(message("config.startup.empty"))
        cellRenderer = ColoredListCellRendererWithSpeedSearch<Pair<String, Int>> { value ->
            value?.also {
                append(it.first)
                append(" -> ", SimpleTextAttributes.GRAY_ATTRIBUTES)
                append(it.second.toString())
            }
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val selection = !isSelectionEmpty
                if (selection) editStartup()
                return selection
            }
        }.installOn(this)
        ListSpeedSearch(this).setClearSearchOnNavigateNoMatch(true)
    }

    private val contentTreeModel = DefaultTreeModel(ShadowLocationRoot)
    private val contentTree = CheckboxTree(object : CheckboxTree.CheckboxTreeCellRenderer(true) {
        override fun customizeRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            when (value) {
                is ShadowLocation -> textRenderer.append(value.location.identifier)
                is ShadowBundle -> {
                    textRenderer.append(value.bundle.canonicalName)

                    value.sourceBundle?.bundleVersion?.also {
                        textRenderer.append(" / source: [$it]", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    }
                }
            }
            SpeedSearchUtil.applySpeedSearchHighlighting(tree, textRenderer, false, selected)
        }
    }, null).apply { model = contentTreeModel }
    private val sourceVersionField = ComboBox<BundleDefinition>().apply {
        renderer = ColoredListCellRendererWithSpeedSearch<BundleDefinition> { value ->
            value?.canonicalName?.also { append(it) }
        }
        isEnabled = false
    }
    private val sourceVersionComponent = LabeledComponent.create(
        sourceVersionField, message("config.content.sourceVersion"), BorderLayout.WEST
    )

    init {
        // Target tab
        val reloadActionButton = object : AnActionButton(message("config.target.reload"), AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = locationList.selectedValue.let {
                it.backgroundResolve(project, onFinished = {
                    locationModified += it to it
                    updateComboBox()
                })
            }
        }.apply {
            isEnabled = false
            locationList.addListSelectionListener { isEnabled = locationList.isSelectionEmpty.not() }
        }

        val launcherPanel = VerticalBox().apply {
            add(launcherJar)
            add(launcher)
        }
        val locationsPanel = BorderLayoutPanel().withBorder(
            IdeBorderFactory.createTitledBorder(
                message("config.target.borderHint"), false, JBUI.insetsTop(8)
            ).setShowLine(true)
        ).addToCenter(ToolbarDecorator.createDecorator(locationList).setAddAction { addLocation() }
                          .setRemoveAction { removeLocation() }.setEditAction { editLocation() }
                          .addExtraAction(reloadActionButton).createPanel()).addToBottom(launcherPanel)

        panel.addTab(message("config.target.tab"), locationsPanel)


        // Startup tab
        val startupPanel = BorderLayoutPanel().withBorder(
            IdeBorderFactory.createTitledBorder(
                message("config.startup.borderHint"), false, JBUI.insetsTop(8)
            ).setShowLine(true)
        ).addToCenter(ToolbarDecorator.createDecorator(startupList).setAddAction { addStartup() }
                          .setRemoveAction { removeStartup() }.setEditAction { editStartup() }.createPanel())

        panel.addTab(message("config.startup.tab"), startupPanel)


        // Content tab
        val contentPanel = BorderLayoutPanel().withBorder(
            IdeBorderFactory.createTitledBorder(
                message("config.content.borderHint"), false, JBUI.insetsTop(8)
            ).setShowLine(true)
        ).addToCenter(
            ToolbarDecorator.createDecorator(contentTree).disableAddAction().disableRemoveAction()
                .disableUpDownActions().addExtraActions(object : AnActionButton(
                    message("config.target.reload"), AllIcons.Actions.Refresh
                ) {
                    override fun actionPerformed(e: AnActionEvent) = reloadContentList()
                }, object : AnActionButton(message("config.content.reload"), AllIcons.Actions.ForceRefresh) {
                    override fun actionPerformed(e: AnActionEvent) = reloadContentListByDefaultRule()
                }).createPanel()
        ).addToBottom(sourceVersionComponent)

        var selectedShadowBundle: ShadowBundle? = null
        sourceVersionField.selectedItemProperty().adviseEternal {
            selectedShadowBundle?.apply {
                sourceBundle = it
                contentTreeModel.reload(this)
            }
        }
        contentTree.addTreeSelectionListener {
            selectedShadowBundle = null
            sourceVersionField.apply {
                removeAllItems()

                isEnabled = (it.path?.lastPathComponent as? ShadowBundle)?.let { bundle ->
                    addItem(null)
                    ShadowLocationRoot.sourceVersions[bundle.bundle.bundleSymbolicName]?.forEach(this::addItem)
                    item = bundle.sourceBundle
                    selectedShadowBundle = bundle
                    true
                } ?: false
            }
        }

        panel.addTab(message("config.content.tab"), contentPanel)


        // Anchor
        launcherAnchor = UIUtil.mergeComponentsWithAnchor(launcherJar, launcher)
    }

    override fun createComponent(): JComponent = panel
    override fun getDisplayName(): String = message("config.displayName")
    override fun getId(): String = "cn.varsa.idea.pde.partial.plugin.config.TargetConfigurable"
    override fun getHelpTopic(): String = id

    override fun getAnchor(): JComponent? = launcherAnchor
    override fun setAnchor(anchor: JComponent?) {
        launcherAnchor = anchor

        launcherJar.anchor = anchor
        launcher.anchor = anchor
    }

    override fun isModified(): Boolean {
        locationModified.isNotEmpty().ifTrue { return true }
        if (launcherJarCombo.item != service.launcherJar || launcherCombo.item != service.launcher) return true

        val locations = locationModel.elements().toList()
        if (locations.size != service.locations.size) return true
        locations.run { mapIndexed { index, def -> def != service.locations[index] }.any { it } }.ifTrue { return true }

        val startups = startupModel.elements().toList()
        if (startups.size != service.startupLevels.size) return true
        service.startupLevels.entries.run { mapIndexed { index, entry -> startups[index].run { first != entry.key || second != entry.value } } }
            .any { it }.ifTrue { return true }

        ShadowLocationRoot.locations.any { it.isModify }.ifTrue { return true }

        return false
    }

    override fun apply() {
        service.launcherJar = launcherJarCombo.item
        service.launcher = launcherCombo.item

        service.locations.also {
            it.clear()
            it += locationModel.elements().toList()
        }

        service.startupLevels.also {
            it.clear()
            it += startupModel.elements().toList()
        }

        ShadowLocationRoot.locations.forEach(ShadowLocation::apply)

        TargetDefinitionChangeListener.notifyLocationsChanged(project)
        locationModified.clear()
    }

    override fun reset() {
        locationModel.clear()
        locationModified.clear()
        startupModel.clear()

        ShadowLocationRoot.also {
            it.removeAllChildren()
            it.sourceVersions.clear()
        }

        launcherJarCombo.item = service.launcherJar
        launcherCombo.item = service.launcher

        service.locations.forEach {
            locationModel.addElement(it)
            ShadowLocationRoot.addLocation(it)
        }
        service.startupLevels.forEach { startupModel.addElement(it.toPair()) }

        updateComboBox()
        reloadContentList()
        ShadowLocationRoot.sort()
        contentTreeModel.reload()
    }

    private fun reloadContentList() {
        ShadowLocationRoot.locations.forEach { location ->
            location.reset()
            location.bundles.filter { it.sourceBundle == null }.forEach { bundle ->
                bundle.sourceBundle = ShadowLocationRoot.sourceVersions[bundle.bundle.bundleSymbolicName]?.let { set ->
                    set.firstOrNull { it.bundleVersion == bundle.bundle.bundleVersion }
                }
            }
            contentTreeModel.reload(location)
        }
    }

    private fun reloadContentListByDefaultRule() {
        ShadowLocationRoot.locations.forEach { location ->
            location.reset()
            location.bundles.forEach { bundle ->
                bundle.sourceBundle = ShadowLocationRoot.sourceVersions[bundle.bundle.bundleSymbolicName]?.let { set ->
                    set.firstOrNull { it.bundleVersion == bundle.bundle.bundleVersion }
                }
            }
            contentTreeModel.reload(location)
        }
    }

    private fun updateComboBox() {
        launcherJarCombo.also { comboBox ->
            comboBox.removeAllItems()
            locationModel.elements().toList().mapNotNull(TargetLocationDefinition::launcherJar).distinct()
                .forEach(comboBox::addItem)
        }

        launcherCombo.also { comboBox ->
            comboBox.removeAllItems()
            locationModel.elements().toList().mapNotNull(TargetLocationDefinition::launcher).distinct()
                .forEach(comboBox::addItem)
        }
    }

    private fun addLocation() {
        val dialog = EditLocationDialog()
        if (dialog.showAndGet()) {
            val location = dialog.getNewLocation()

            locationModel.addElement(location)
            locationList.setSelectedValue(location, true)
            locationModified += Pair(null, location)

            location.backgroundResolve(project, onFinished = {
                updateComboBox()
                ShadowLocationRoot.addLocation(location)
                ShadowLocationRoot.sort()
                contentTreeModel.reload()
            })
        }
    }

    private fun removeLocation() {
        if (locationList.isSelectionEmpty.not()) {
            val location = locationList.selectedValue
            locationModel.removeElement(location)
            locationModified += Pair(location, null)

            updateComboBox()
            ShadowLocationRoot.removeLocation(location)
        }
    }

    private fun editLocation() {
        if (locationList.isSelectionEmpty.not()) {
            val index = locationList.selectedIndex
            val value = locationList.selectedValue

            val dialog = EditLocationDialog(defaultPath = value.location, defaultAlis = value.alias ?: "")
            if (dialog.showAndGet()) {
                val location = dialog.getNewLocation()

                locationModel.set(index, location)
                locationList.setSelectedValue(location, true)
                locationModified += Pair(value, location)

                location.backgroundResolve(project, onFinished = {
                    updateComboBox()
                    ShadowLocationRoot.replaceLocation(location, value)
                    ShadowLocationRoot.sort()
                    contentTreeModel.reload()
                })
            }
        }
    }

    private fun addStartup() {
        val dialog = EditStartupDialog()
        if (dialog.showAndGet()) {
            val level = dialog.getNewLevel()

            val index = startupModel.elements().toList().indexOfFirst { it.first == level.first }
            if (index > -1) {
                startupModel.set(index, level)
            } else {
                startupModel.addElement(level)
            }

            startupList.setSelectedValue(level, true)
        }
    }

    private fun removeStartup() {
        if (startupList.isSelectionEmpty.not()) {
            startupModel.removeElement(startupList.selectedValue)
        }
    }

    private fun editStartup() {
        if (startupList.isSelectionEmpty.not()) {
            val index = startupList.selectedIndex
            val value = startupList.selectedValue

            val dialog = EditStartupDialog(symbolicName = value.first, level = value.second)
            if (dialog.showAndGet()) {
                val level = dialog.getNewLevel()

                startupModel.set(index, level)
                startupList.setSelectedValue(level, true)
            }
        }
    }

    inner class EditLocationDialog(
        title: String = message("config.target.locationDialog.title"),
        private val description: String = message("config.target.locationDialog.description"),
        defaultPath: String = "",
        defaultAlis: String = ""
    ) : DialogWrapper(project), PanelWithAnchor {
        private val fileDescription = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        private var myAnchor: JComponent? = null

        private val aliasField = JBTextField(defaultAlis)
        private val aliasComponent =
            LabeledComponent.create(aliasField, message("config.target.locationDialog.alias"), BorderLayout.WEST)

        private val pathField =
            FileChooserFactory.getInstance().createFileTextField(fileDescription, myDisposable).field.apply {
                columns = 25
                text = defaultPath
            }
        private val pathComponent = TextFieldWithBrowseButton(pathField).apply {
            addBrowseFolderListener(title, description, project, fileDescription)
        }.let { LabeledComponent.create(it, description, BorderLayout.WEST) }

        private val dependencyComboBox = ComboBox(DependencyScope.values().map { it.displayName }.toTypedArray())
        private val dependencyComponent = LabeledComponent.create(
            dependencyComboBox, message("config.target.locationDialog.dependency"), BorderLayout.WEST
        )

        init {
            setTitle(title)
            init()

            anchor = UIUtil.mergeComponentsWithAnchor(aliasComponent, pathComponent, dependencyComponent)
        }

        override fun createCenterPanel(): JComponent = VerticalBox().apply {
            add(aliasComponent)
            add(pathComponent)
            add(dependencyComponent)
        }

        override fun getAnchor(): JComponent? = myAnchor
        override fun setAnchor(anchor: JComponent?) {
            myAnchor = anchor

            aliasComponent.anchor = anchor
            pathComponent.anchor = anchor
            dependencyComponent.anchor = anchor
        }

        fun getNewLocation(): TargetLocationDefinition = TargetLocationDefinition(pathField.text).apply {
            alias = aliasField.text
            dependency = dependencyComboBox.item ?: DependencyScope.COMPILE.displayName
        }
    }

    inner class EditStartupDialog(
        title: String = message("config.target.startupDialog.title"),
        description: String = message("config.target.startupDialog.description"),
        symbolicName: String = "",
        level: Int = 4,
    ) : DialogWrapper(project), PanelWithAnchor {
        private var myAnchor: JComponent? = null

        private val nameTextField = JBTextField(symbolicName)
        private val levelSpinner = JBIntSpinner(level, -1, Int.MAX_VALUE, 1)

        private val nameComponent = LabeledComponent.create(nameTextField, description, BorderLayout.WEST)
        private val levelComponent =
            LabeledComponent.create(levelSpinner, message("config.target.startupDialog.level"), BorderLayout.WEST)

        init {
            setTitle(title)
            init()

            anchor = UIUtil.mergeComponentsWithAnchor(nameComponent, levelComponent)
        }

        override fun createCenterPanel(): JComponent = VerticalBox().apply {
            add(nameComponent)
            add(levelComponent)
        }

        override fun getAnchor(): JComponent? = myAnchor
        override fun setAnchor(anchor: JComponent?) {
            myAnchor = anchor

            nameComponent.anchor = anchor
            levelComponent.anchor = anchor
        }

        fun getNewLevel(): Pair<String, Int> = Pair(nameTextField.text, levelSpinner.number)
    }

    private object ShadowLocationRoot : CheckedTreeNode() {
        val sourceVersions = hashMapOf<String, HashSet<BundleDefinition>>()
        val locations get() = children?.map { it as ShadowLocation } ?: emptyList()

        fun sort() = children?.also { Collections.sort(it, Comparator.comparing(TreeNode::toString)) }

        fun addLocation(location: TargetLocationDefinition): ShadowLocation = ShadowLocation(location).apply {
            location.bundles.sortedBy { it.canonicalName }.forEach {
                val eclipseSourceBundle = it.manifest?.eclipseSourceBundle
                if (eclipseSourceBundle != null) {
                    sourceVersions.computeIfAbsent(eclipseSourceBundle.key) { hashSetOf() } += it
                } else {
                    add(ShadowBundle(this, it).apply {
                        isChecked = !location.bundleUnSelected.contains(it.canonicalName)
                    })
                }
            }

            bundles.forEach { bundle ->
                bundle.sourceBundle =
                    sourceVersions[bundle.bundle.bundleSymbolicName]?.firstOrNull { it.bundleVersion == bundle.bundle.bundleVersion }
            }
        }.also { ShadowLocationRoot.add(it) }

        fun removeLocation(location: TargetLocationDefinition): ShadowLocation {
            sourceVersions.values.forEach { it -= location.bundles }
            return locations.first { it.location == location }.also { ShadowLocationRoot.remove(it) }
        }

        fun replaceLocation(addedLocation: TargetLocationDefinition, removedLocation: TargetLocationDefinition) {
            val oldLocation = removeLocation(removedLocation)
            val newLocation = addLocation(addedLocation)

            val names = addedLocation.bundles.map { it.canonicalName }
            addedLocation.bundleUnSelected += removedLocation.bundleUnSelected.filter { names.contains(it) }

            val oldBundlesMap = oldLocation.bundles.associateBy { it.bundle.canonicalName }
            newLocation.bundles.forEach { bundle ->
                val canonicalName = bundle.bundle.canonicalName

                bundle.isChecked =
                    oldBundlesMap[canonicalName]?.isChecked ?: !addedLocation.bundleUnSelected.contains(canonicalName)

                sourceVersions[bundle.bundle.bundleSymbolicName]?.firstOrNull { source ->
                    oldBundlesMap[canonicalName]?.sourceBundle?.bundleVersion == source.bundleVersion
                }?.also { bundle.sourceBundle = it }
            }
        }
    }

    private data class ShadowLocation(val location: TargetLocationDefinition) : CheckedTreeNode() {
        val bundles get() = children?.map { it as ShadowBundle } ?: emptyList()

        val isModify: Boolean
            get() = bundles.any { it.isModify } || location.bundleUnSelected != bundles.filterNot { it.isChecked }
                .map { it.bundle.canonicalName }

        fun reset() {
            bundles.forEach { it.isChecked = !location.bundleUnSelected.contains(it.bundle.canonicalName) }
            bundles.forEach(ShadowBundle::reset)
        }

        fun apply() {
            location.bundleUnSelected.clear()
            location.bundleUnSelected += bundles.filterNot { it.isChecked }.map { it.bundle.canonicalName }

            location.bundleVersionSelection.clear()
            location.bundleVersionSelection += bundles.filterNot { it.sourceBundle == null }
                .associate { it.bundle.canonicalName to it.sourceBundle!!.bundleVersion.toString() }

            bundles.forEach(ShadowBundle::apply)
        }

        override fun toString(): String = location.identifier
    }

    private data class ShadowBundle(val location: ShadowLocation, val bundle: BundleDefinition) : CheckedTreeNode() {
        var sourceBundle: BundleDefinition? = null

        val isModify: Boolean get() = sourceBundle != bundle.sourceBundle

        fun reset() {
            sourceBundle = bundle.sourceBundle
        }

        fun apply() {
            bundle.sourceBundle = sourceBundle
        }

        override fun toString(): String = bundle.canonicalName
    }
}
