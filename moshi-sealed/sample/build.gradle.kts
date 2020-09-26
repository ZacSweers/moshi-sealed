/*
 * Copyright (c) 2019 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
  kotlin("kapt")
}

dependencies {
  kapt(project(":moshi-sealed:codegen"))
  kapt(Dependencies.Moshi.codegen)

  implementation(project(":moshi-sealed:annotations"))
  implementation(Dependencies.Moshi.kotlin)
  implementation(project(":moshi-sealed:reflect"))

  kaptTest(project(":moshi-sealed:codegen"))
  testImplementation(Dependencies.Testing.junit)
  testImplementation(Dependencies.Testing.truth)
}

kapt {
  arguments {
    arg("moshi.generated", "javax.annotation.Generated")
  }
}

tasks.withType<KotlinCompile>().configureEach {
  kotlinOptions {
    @Suppress("SuspiciousCollectionReassignment")
    freeCompilerArgs += "-Xopt-in=kotlin.ExperimentalStdlibApi"
  }
}