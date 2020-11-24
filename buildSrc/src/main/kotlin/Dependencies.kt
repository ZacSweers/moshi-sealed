/*
 * Copyright (C) 2020 Zac Sweers
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

object Dependencies {

  const val autoCommon = "com.google.auto:auto-common:0.11"
  const val asm = "org.ow2.asm:asm:7.1"

  object Asm {
    private const val version = "7.1"
    const val asm = "org.ow2.asm:asm:$version"
    const val util = "org.ow2.asm:asm-util:$version"
  }

  object AutoService {
    private const val version = "1.0-rc7"
    const val annotations = "com.google.auto.service:auto-service-annotations:$version"
    const val processor = "com.google.auto.service:auto-service:$version"
  }

  object Incap {
    private const val version = "0.3"
    const val annotations = "net.ltgt.gradle.incap:incap:$version"
    const val processor = "net.ltgt.gradle.incap:incap-processor:$version"
  }

  object Kotlin {
    const val version = "1.4.10"
    const val dokkaVersion = "1.4.10"
    const val reflect = "org.jetbrains.kotlin:kotlin-reflect:$version"
    const val metadata = "org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.1.0"
    const val compilerEmbeddable = "org.jetbrains.kotlin:kotlin-compiler-embeddable:$version"
    const val jvmTarget = "1.8"
    val defaultFreeCompilerArgs = listOf("-Xjsr305=strict", "-progressive")

    object Ksp {
      const val version = "1.4.10-dev-experimental-20201110"
      const val api = "com.google.devtools.ksp:symbol-processing-api:$version"
      const val ksp = "com.google.devtools.ksp:symbol-processing:$version"
    }
  }

  object KotlinPoet {
    private const val version = "1.7.2"
    const val kotlinPoet = "com.squareup:kotlinpoet:$version"
    const val metadata = "com.squareup:kotlinpoet-metadata-specs:$version"
    const val metadataSpecs = "com.squareup:kotlinpoet-metadata-specs:$version"
  }

  object Moshi {
    private const val version = "1.11.0"
    const val moshi = "com.squareup.moshi:moshi:$version"
    const val kotlin = "com.squareup.moshi:moshi-kotlin:$version"
    const val codegen = "com.squareup.moshi:moshi-kotlin-codegen:$version"
    const val adapters = "com.squareup.moshi:moshi-adapters:$version"
  }

  object Testing {
    const val compileTesting = "com.github.tschuchortdev:kotlin-compile-testing:1.3.1"
    const val kspCompileTesting = "com.github.tschuchortdev:kotlin-compile-testing-ksp:1.3.1"
    const val junit = "junit:junit:4.13.1"
    const val truth = "com.google.truth:truth:1.1"
  }
}
