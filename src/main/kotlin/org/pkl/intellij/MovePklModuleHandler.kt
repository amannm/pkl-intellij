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
package org.pkl.intellij

import java.net.URI
import java.nio.file.Path
import java.util.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.removeUserData
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.refactoring.util.MoveRenameUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.withPath
import org.pkl.intellij.psi.*

val originalModuleDirectoryKey: Key<Path> = Key.create("ORIGINAL_MODULE_DIRECTORY")

class MovePklModuleHandler : MoveFileHandler() {

  override fun canProcessElement(element: PsiFile): Boolean {
    return element is PklModule
  }

  override fun prepareMovedFile(
    file: PsiFile,
    moveDestination: PsiDirectory,
    oldToNewMap: MutableMap<PsiElement, PsiElement>
  ) {
    if (file is PklModule) {
      val originalModuleDirectory = file.getUserData(originalModuleDirectoryKey)
      if (originalModuleDirectory == null) {
        val moduleDirectoryPath = file.containingDirectory?.virtualFile?.toNioPath()?.toAbsolutePath() ?: return
        file.putUserData(originalModuleDirectoryKey, moduleDirectoryPath)
      }
    }
  }

  override fun findUsages(
    psiFile: PsiFile,
    newParent: PsiDirectory,
    searchInComments: Boolean,
    searchInNonJavaFiles: Boolean
  ): List<UsageInfo>? {
    val searchScope = GlobalSearchScope.projectScope(psiFile.project)
    val usages = ReferencesSearch.search(psiFile, searchScope, false)
      .asSequence()
      .filterIsInstance<PklModuleUriReference>()
      .distinct()
      .map {
        val range = it.rangeInElement
        MoveRenameUsageInfo(it.element, it, range.startOffset, range.endOffset, psiFile, false)
      }
      .toList()
      .ifEmpty { null }
    return usages
  }

  override fun retargetUsages(
    usageInfos: List<UsageInfo>,
    oldToNewMap: Map<PsiElement, PsiElement>
  ) {
    usageInfos.filterIsInstance<MoveRenameUsageInfo>().forEach {
      val ref = it.reference as? PklModuleUriReference ?: return@forEach
      val ele = it.referencedElement as? PklModule ?: return@forEach
      try {
        // the module containing the ref itself is being moved
        val refModule = ref.element.containingFile
        val refModuleMoving = refModule.getUserData(originalModuleDirectoryKey) != null
        if (!refModuleMoving) {
          ref.bindToElement(ele)
        }
      } catch (ex: IncorrectOperationException) {
        LOG.error(ex)
      }
    }
  }

  @Throws(IncorrectOperationException::class)
  override fun updateMovedFile(file: PsiFile) {
    if (file is PklModule) {
      val oldModuleDirectoryPath = file.removeUserData(originalModuleDirectoryKey) ?: return
      val imports = file.imports.toList()
      if (imports.isNotEmpty()) {
        val newModuleDirectoryPath = file.containingDirectory?.virtualFile?.toNioPath()?.toAbsolutePath() ?: return
        val offsetPath = newModuleDirectoryPath.relativize(oldModuleDirectoryPath).normalize()
        WriteCommandAction.writeCommandAction(file.project).compute<Array<PsiElement>, RuntimeException> {
          imports.forEach { import -> rewriteRelativeImport(import, offsetPath) }
          arrayOf(file)
        }
      }
    }
  }

  private fun rewriteRelativeImport(existingImport: PklImport, offsetPath: Path) {
    val moduleUri = existingImport.moduleUri ?: return
    val existingOtherModuleUri = moduleUri.getModuleUri()?.let { URI.create(it) } ?: return
    val existingOtherModulePath = Path.of(existingOtherModuleUri.path)
    if (existingOtherModulePath.isAbsolute) return
    val newOtherModulePath = offsetPath.resolve(existingOtherModulePath).normalize()
    val newOtherModelUri = existingOtherModuleUri.withPath(newOtherModulePath.toString())
    val newConstant =
      PklPsiFactory.createStringConstant(newOtherModelUri.toString(), existingImport.project)
    moduleUri.stringConstant.replace(newConstant)
  }

  companion object {
    private val LOG = Logger.getInstance(MovePklModuleHandler::class.java)
  }
}