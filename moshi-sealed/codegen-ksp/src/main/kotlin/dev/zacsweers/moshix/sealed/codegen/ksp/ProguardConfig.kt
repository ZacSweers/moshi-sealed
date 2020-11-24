/*
 * Copyright (C) 2020 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.moshix.sealed.codegen.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.ClassName

/**
 * Represents a proguard configuration for a given spec. This covers two main areas:
 * - Keeping the target class name to Moshi's reflective lookup of the adapter.
 * - Keeping the generated adapter class name + public constructor for reflective lookup.
 *
 * Each rule is intended to be as specific and targeted as possible to reduce footprint, and each is
 * conditioned on usage of the original target type.
 *
 * To keep this processor as an ISOLATING incremental processor, we generate one file per target
 * class with a deterministic name (see [outputFile]) with an appropriate originating element.
 */
internal data class ProguardConfig(
  val targetClass: ClassName,
  val adapterName: String
) {
  private val outputFile = "META-INF/proguard/moshi-sealed-${targetClass.canonicalName}.pro"

  /** Writes this to `filer`. */
  fun writeTo(codeGenerator: CodeGenerator) {
    codeGenerator.createNewFile("", outputFile, "").bufferedWriter()
      .use(::writeTo)
  }

  private fun writeTo(out: Appendable): Unit = out.run {
    //
    // -if class {the target class}
    // -keepnames class {the target class}
    //
    val targetName = targetClass.reflectionName()
    // Keep the class name for Moshi's reflective lookup based on it
    appendLine("-if class $targetName")
    appendLine("-keepnames class $targetName")
  }
}
