package cn.varsa.idea.pde.partial.plugin.resolver

import cn.varsa.idea.pde.partial.common.*
import cn.varsa.idea.pde.partial.plugin.cache.*
import cn.varsa.idea.pde.partial.plugin.config.*
import cn.varsa.idea.pde.partial.plugin.facet.*
import cn.varsa.idea.pde.partial.plugin.i18n.EclipsePDEPartialBundles.message
import cn.varsa.idea.pde.partial.plugin.openapi.*
import cn.varsa.idea.pde.partial.plugin.support.*
import com.intellij.openapi.module.*
import com.intellij.openapi.roots.*

class PdeModuleRuntimeLibraryResolver : ManifestLibraryResolver {
    override val displayName: String = message("resolver.pde.moduleRuntime")

    override fun preResolve(area: Module) {
        PDEFacet.getInstance(area) ?: return

        area.updateModel { model ->
            model.orderEntries.filter { it is ModuleOrderEntry || it.presentableName.startsWith(ProjectLibraryNamePrefix) }
                .forEach { model.removeOrderEntry(it) }
        }
    }

    override fun resolve(area: Module) {
        PDEFacet.getInstance(area) ?: return

        val cacheService = BundleManifestCacheService.getInstance(area.project)
        val managementService = BundleManagementService.getInstance(area.project)

        val classesRoot = readCompute { cacheService.getManifest(area) }?.bundleClassPath?.keys?.filterNot { it == "." }
            ?.flatMap { binaryName ->
                area.moduleRootManager.contentRoots.mapNotNull { it.findFileByRelativePath(binaryName) }
            }?.map { it.protocolUrl }?.distinct() ?: emptyList()

        area.updateModel { model ->
            val libraryTableModel = model.moduleLibraryTable.modifiableModel

            val library = libraryTableModel.getLibraryByName(ModuleLibraryName) ?: writeCompute {
                libraryTableModel.createLibrary(ModuleLibraryName)
            }

            model.findLibraryOrderEntry(library)?.apply {
                scope = DependencyScope.COMPILE
                isExported = true
            }

            val libraryModel = library.modifiableModel

            libraryModel.getUrls(OrderRootType.CLASSES).forEach { libraryModel.removeRoot(it, OrderRootType.CLASSES) }
            classesRoot.forEach { libraryModel.addRoot(it, OrderRootType.CLASSES) }

            applicationInvokeAndWait {
                writeRun {
                    libraryModel.commit()
                    libraryTableModel.commit()
                }
            }

            val bundleRequiredOrFromReExportOrderedList = readCompute { area.bundleRequiredOrFromReExportOrderedList }
            applicationInvokeAndWait {
                area.project.allPDEModules().filterNot { it == area }
                    .filter { bundleRequiredOrFromReExportOrderedList.contains(readCompute { cacheService.getManifest(it)?.bundleSymbolicName?.key }) }
                    .forEach { model.addModuleOrderEntry(it) }
            }

            area.project.libraryTable().libraries.filter { it.name?.startsWith(ProjectLibraryNamePrefix) == true }
                .forEach { depLibrary ->
                    managementService.bundles[depLibrary.name?.substringAfter(ProjectLibraryNamePrefix)]?.dependencyScope?.also {
                        model.addLibraryEntry(depLibrary).apply {
                            scope = it
                            isExported = false
                        }
                    }
                }
        }
    }

    override fun postResolve(area: Module) {
        PDEFacet.getInstance(area) ?: return

        area.updateModel { model ->
            val orderEntries = model.orderEntries.toMutableList()
            val orderEntriesMap = orderEntries.associateBy { it.presentableName }

            val kotlinOrder = orderEntriesMap.filter { it.key.startsWith(KotlinOrderEntryName) }.values
            val runtimeOrder = orderEntriesMap[ModuleLibraryName]
            val dependencyOrder = readCompute { area.bundleRequiredOrFromReExportOrderedList }.mapNotNull {
                orderEntriesMap[it] ?: orderEntriesMap["$ProjectLibraryNamePrefix$it"]
            }

            var libraryIndex = orderEntries.indexOfLast { it is JdkOrderEntry || it is ModuleSourceOrderEntry } + 1
            val arrangeOrderEntries = orderEntries.apply {
                removeAll(kotlinOrder)
                addAll(libraryIndex, kotlinOrder)
                libraryIndex += kotlinOrder.size

                runtimeOrder?.also {
                    remove(it)
                    add(libraryIndex++, it)
                }

                removeAll(dependencyOrder)
                addAll(libraryIndex, dependencyOrder)
            }.toTypedArray()
            model.rearrangeOrderEntries(arrangeOrderEntries)
        }
    }
}
