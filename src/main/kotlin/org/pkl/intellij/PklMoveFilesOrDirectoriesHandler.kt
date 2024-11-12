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

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesHandler

class PklMoveFilesOrDirectoriesHandler : MoveFilesOrDirectoriesHandler() {

  override fun adjustForMove(
    project: Project?,
    sourceElements: Array<out PsiElement>?,
    targetElement: PsiElement?
  ): Array<PsiElement>? {
    sourceElements?.forEach {annotateForMove(it)}
    return super.adjustForMove(project, sourceElements, targetElement)
  }
}

private fun annotateForMove(element: PsiElement) {
  when (element) {
    is PsiFile -> {
      val originalPath = element.containingDirectory?.virtualFile?.toNioPath()?.toAbsolutePath() ?: return
      element.putUserData(originalModuleDirectoryKey, originalPath)
    }
    is PsiDirectory -> {
      element.children.forEach { annotateForMove(it) }
    }
  }
}