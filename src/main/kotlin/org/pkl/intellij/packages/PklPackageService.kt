/**
 * Copyright © 2024 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.intellij.packages

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.*
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import org.pkl.intellij.PklFileType
import org.pkl.intellij.cacheKeyService
import org.pkl.intellij.packages.PklProjectService.Companion.PKL_PROJECTS_TOPIC
import org.pkl.intellij.packages.dto.PackageMetadata
import org.pkl.intellij.packages.dto.PackageUri
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.PklModuleUri
import org.pkl.intellij.stubs.PklModuleUriIndex
import org.pkl.intellij.toolchain.pklCli
import org.pkl.intellij.util.noCacheResult
import org.pkl.intellij.util.pklCacheDir

val Project.pklPackageService: PklPackageService
  get() = service()

data class PackageLibraryRoots(
  val zipFile: VirtualFile,
  val metadataFile: VirtualFile,
  val packageRoot: VirtualFile
)

internal val localFs: LocalFileSystem by lazy { LocalFileSystem.getInstance() }

/**
 * Keeps track of all packages used within a project.
 *
 * There are two types of packages:
 * 1. Packages declared as dependencies of a Pkl Project
 * 2. Packages imported via their absolute URI
 *
 * Packages that are dependencies of a project are tracked with the help of
 * [org.pkl.intellij.packages.PklProjectService], and any updates are synchronized by listening to
 * [PKL_PROJECTS_TOPIC].
 *
 * Packages that are imported directly via absolute URIs are looked up via the stub index API. We
 * re-query the index any time a change to a Pkl file occurs.
 */
@Service(Service.Level.PROJECT)
class PklPackageService(val project: Project) : Disposable, UserDataHolderBase() {
  companion object {
    fun getInstance(project: Project): PklPackageService = project.service()

    private val packageMetadataCachedValuesProvider:
      ParameterizedCachedValueProvider<PackageMetadata, Pair<Project, PackageDependency>> =
      ParameterizedCachedValueProvider { (project, dep) ->
        val roots =
          project.pklPackageService.getLibraryRoots(dep)
            ?: return@ParameterizedCachedValueProvider noCacheResult()
        CachedValueProvider.Result.create(
          PackageMetadata.load(roots.metadataFile),
          roots.metadataFile
        )
      }

    private val libraryRootsProvider:
      ParameterizedCachedValueProvider<PackageLibraryRoots, Pair<Project, PackageDependency>> =
      ParameterizedCachedValueProvider { (project, dep) ->
        val roots =
          project.pklPackageService.doGetRoots(dep)
            ?: return@ParameterizedCachedValueProvider noCacheResult()
        CachedValueProvider.Result.create(roots, roots.zipFile, roots.metadataFile)
      }

    private val allDepsProvider:
      ParameterizedCachedValueProvider<List<PackageDependency>, Pair<Project, PackageDependency>> =
      ParameterizedCachedValueProvider { (project, dep) ->
        val svc = project.pklPackageService
        val libraryRoots =
          svc.getLibraryRoots(dep) ?: return@ParameterizedCachedValueProvider noCacheResult()
        val metadata =
          svc.getPackageMetadata(dep) ?: return@ParameterizedCachedValueProvider noCacheResult()
        val dependencies = metadata.dependencies.values.map { it.uri.asPackageDependency() }
        val collectedDeps =
          listOf(dep) +
            dependencies.flatMap { svc.collectAllDependenciesOfPackage(it) ?: emptyList() }
        CachedValueProvider.Result.create(collectedDeps, libraryRoots.metadataFile)
      }
  }

  private val dumbService: DumbService = project.service()

  private val cachedValuesManager = CachedValuesManager.getManager(project)

  init {
    project.messageBus.connect(this).apply {
      subscribe(
        PKL_PROJECTS_TOPIC,
        object : PklProjectListener {
          override fun pklProjectsUpdated(
            service: PklProjectService,
            projects: Map<String, PklProject>
          ) {
            projectPackages =
              projects.values.flatMap { pklProject ->
                if (pklProject.projectDeps == null) emptyList()
                else
                  pklProject.projectDeps.resolvedDependencies.values.mapNotNull { dep ->
                    (dep as? PklProject.Companion.RemoteDependency)?.toDependency(pklProject)
                      as? PackageDependency
                  }
              }
          }
        }
      )
    }
    val self = this
    PsiManager.getInstance(project)
      .addPsiTreeChangeListener(
        object : PsiTreeChangeAdapter() {
          override fun childrenChanged(event: PsiTreeChangeEvent) {
            synchronized(self) {
              if (event.file?.fileType == PklFileType) {
                if (timerTask != null) {
                  timerTask!!.cancel()
                }
                timerTask =
                  object : TimerTask() {
                    override fun run() {
                      refreshDeclaredPackages()
                    }
                  }
                try {
                  timer.schedule(timerTask, Duration.ofSeconds(3).toMillis())
                } catch (e: IllegalStateException) {
                  thisLogger()
                    .warn(
                      "IllegalStateException when attempting to schedule task to refresh declared packages: $e"
                    )
                  timer.cancel()
                  timer = Timer()
                }
              }
            }
          }
        },
        this
      )
  }

  fun allPackages(): List<PackageDependency> {
    return projectPackages + declaredPackages
  }

  fun downloadPackage(packages: List<PackageUri>): CompletableFuture<Unit> {
    return CompletableFuture<Unit>().apply {
      runBackgroundableTask("Download packages") {
        try {
          project.pklCli.downloadPackage(packages)
          complete(Unit)
        } catch (e: Throwable) {
          completeExceptionally(e)
        }
      }
    }
  }

  fun downloadPackage(packageUri: PackageUri): CompletableFuture<Unit> {
    return CompletableFuture<Unit>().apply {
      runBackgroundableTask("download $packageUri") {
        try {
          project.pklCli.downloadPackage(listOf(packageUri))
          complete(Unit)
        } catch (e: Throwable) {
          completeExceptionally(e)
        }
      }
    }
  }

  fun isInPackage(file: VirtualFile): Boolean = getDirectlyImportedPackage(file) != null

  /** Returns the package that [file] resides in, if exists. */
  fun getDirectlyImportedPackage(file: VirtualFile): PackageDependency? {
    val root = projectRootManager.fileIndex.getSourceRootForFile(file) ?: return null
    return allPackages().find { it.getRoot(project) == root }
  }

  /** Packages that are dependencies of a project */
  private var projectPackages: List<PackageDependency> = emptyList()

  /** Packages imported via absolute URI */
  private var declaredPackages: List<PackageDependency> = emptyList()

  private val projectRootManager = ProjectRootManager.getInstance(project)

  private fun collectAllDependenciesOfPackage(dep: PackageDependency): List<PackageDependency>? =
    cachedValuesManager.getParameterizedCachedValue(
      this,
      project.cacheKeyService.getKey(
        "PklPackageService.collectAllDependenciesOfPackage",
        dep.packageUri.toString()
      ),
      allDepsProvider,
      false,
      project to dep
    )

  private var timerTask: TimerTask? = null

  private var timer: Timer = Timer()

  private fun refreshDeclaredPackages() {
    // skip if IntelliJ is still indexing
    if (dumbService.isDumb) return
    declaredPackages = runReadAction {
      val moduleUris =
        StubIndex.getInstance().getAllKeys(PklModuleUriIndex.Util.KEY, project).filter { uri ->
          val elems =
            StubIndex.getElements(
              PklModuleUriIndex.Util.KEY,
              uri,
              project,
              GlobalSearchScope.projectScope(project),
              PklModuleUri::class.java
            )
          elems.isNotEmpty()
        }
      moduleUris.flatMap {
        val dep = PackageUri.create(it)?.asPackageDependency() ?: return@flatMap emptyList()
        collectAllDependenciesOfPackage(dep) ?: emptyList()
      }
    }
  }

  override fun dispose() {}

  fun getPackageMetadata(packageDependency: PackageDependency): PackageMetadata? =
    cachedValuesManager.getParameterizedCachedValue(
      this,
      project.cacheKeyService.getKey(
        "PklPackageService.collectAllDependenciesOfPackage",
        packageDependency.packageUri.toString()
      ),
      packageMetadataCachedValuesProvider,
      false,
      project to packageDependency
    )

  fun getResolvedDependencies(
    packageDependency: PackageDependency,
    context: PklProject?
  ): Map<String, Dependency>? {
    val metadata = getPackageMetadata(packageDependency) ?: return null
    if (packageDependency.pklProject != null) {
      return getResolvedDependenciesOfProjectPackage(packageDependency.pklProject, metadata)
    }
    return metadata.dependencies.mapValues { (_, dep) ->
      if (context != null) {
        val resolvedDep = context.projectDeps?.getResolvedDependency(dep.uri) ?: dep
        resolvedDep.toDependency(context) ?: PackageDependency(dep.uri, null, dep.checksums)
      } else {
        PackageDependency(dep.uri, null, dep.checksums)
      }
    }
  }

  private fun doGetRoots(dependency: PackageDependency): PackageLibraryRoots? {
    thisLogger().info("Getting library roots for ${dependency.packageUri}")
    val cacheDir = pklCacheDir?.toNioPath() ?: return null
    val metadataFile =
      dependency.packageUri.relativeMetadataFiles.firstNotNullOfOrNull { path ->
        localFs.findFileByNioFile(cacheDir.resolve(path))
      }
        ?: run {
          val paths = dependency.packageUri.relativeMetadataFiles.map(cacheDir::resolve)
          thisLogger()
            .info("Missing metadata file at paths ${paths.joinToString(", ") { "`$it`" }}")
          return null
        }
    val zipFile =
      dependency.packageUri.relativeZipFiles.firstNotNullOfOrNull { path ->
        localFs.findFileByNioFile(cacheDir.resolve(path))
      }
        ?: run {
          val paths = dependency.packageUri.relativeZipFiles.map(cacheDir::resolve)
          thisLogger().info("Missing zip file at paths ${paths.joinToString(", ") { "`$it`" }}")
          return null
        }
    val jarRoot = jarFs.getJarRootForLocalFile(zipFile) ?: return null
    return PackageLibraryRoots(zipFile, metadataFile, jarRoot)
  }

  fun getLibraryRoots(dependency: PackageDependency): PackageLibraryRoots? =
    cachedValuesManager.getParameterizedCachedValue(
      this,
      project.cacheKeyService.getKey(
        "PklPackageService.getLibraryRoots",
        dependency.packageUri.toString()
      ),
      libraryRootsProvider,
      false,
      project to dependency
    )

  private fun getResolvedDependenciesOfProjectPackage(
    pklProject: PklProject,
    metadata: PackageMetadata
  ): Map<String, Dependency>? {
    val projectDeps = pklProject.projectDeps ?: return null
    return metadata.dependencies.entries.fold(mapOf()) { acc, (name, packageDependency) ->
      val dep =
        projectDeps.getResolvedDependency(packageDependency.uri)?.toDependency(pklProject)
          ?: return@fold acc
      acc.plus(name to dep)
    }
  }
}
