/*
 * Copyright (C) 2021 Zac Sweers
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
package dev.zacsweers.moshix.ksp

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.Origin.KOTLIN
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.moshi.JsonClass
import dev.zacsweers.moshix.ksp.JsonClassSymbolProcessorProvider.Companion.OPTION_GENERATED
import dev.zacsweers.moshix.ksp.JsonClassSymbolProcessorProvider.Companion.OPTION_PROGUARD_CODE_GENERATED
import dev.zacsweers.moshix.ksp.shade.api.AdapterGenerator
import dev.zacsweers.moshix.ksp.shade.api.ProguardConfig
import dev.zacsweers.moshix.ksp.shade.api.PropertyGenerator
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

@AutoService(SymbolProcessorProvider::class)
public class JsonClassSymbolProcessorProvider : SymbolProcessorProvider {
  public companion object {
    /**
     * This annotation processing argument can be specified to have a `@Generated` annotation
     * included in the generated code. It is not encouraged unless you need it for static analysis
     * reasons and not enabled by default.
     *
     * Note that this can only be one of the following values:
     *   * `"javax.annotation.processing.Generated"` (JRE 9+)
     *   * `"javax.annotation.Generated"` (JRE <9)
     */
    public const val OPTION_GENERATED: String = "moshi.generated"

    /**
     * This annotation processing argument can disable proguard rule generating.
     * Normally, this is not recommended unless end-users build their own JsonAdapter look-up tool.
     * This is enabled by default
     */
    public const val OPTION_PROGUARD_CODE_GENERATED: String = "moshi.enabledProguardGenerated"

  }

  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
    return JsonClassSymbolProcessor(environment)
  }
}

private class JsonClassSymbolProcessor(
  environment: SymbolProcessorEnvironment
) : SymbolProcessor {

  private companion object {
    val POSSIBLE_GENERATED_NAMES = setOf(
      "javax.annotation.processing.Generated",
      "javax.annotation.Generated"
    )

    val JSON_CLASS_NAME = JsonClass::class.qualifiedName!!
  }

  private val codeGenerator = environment.codeGenerator
  private val logger = environment.logger
  private val generatedOption = environment.options[OPTION_GENERATED]?.also {
    logger.check(it in POSSIBLE_GENERATED_NAMES) {
      "Invalid option value for $OPTION_GENERATED. Found $it, allowable values are $POSSIBLE_GENERATED_NAMES."
    }
  }
  private val generatedProguard = environment.options[OPTION_PROGUARD_CODE_GENERATED]?.toBooleanStrictOrNull() ?: true

  override fun process(resolver: Resolver): List<KSAnnotated> {
    val generatedAnnotation = generatedOption?.let {
      val annotationType = resolver.getClassDeclarationByName(resolver.getKSNameFromString(it))
        ?: run {
          logger.error("Generated annotation type doesn't exist: $it")
          return emptyList()
        }
      AnnotationSpec.builder(annotationType.toClassName())
        .addMember("value = [%S]", JsonClassSymbolProcessor::class.java.canonicalName)
        .addMember("comments = %S", "https://github.com/square/moshi")
        .build()
    }

    val jsonClassType = resolver.getClassDeclarationByName(
      resolver.getKSNameFromString(JSON_CLASS_NAME)
    )
      ?.asType()
      ?: run {
        logger.error("JsonClass type not found on the classpath.")
        return emptyList()
      }
    resolver.getSymbolsWithAnnotation(JSON_CLASS_NAME)
      .asSequence()
      .forEach { type ->
        logger.check(type is KSClassDeclaration && type.origin == KOTLIN, type) {
          "@JsonClass can't be applied to $type: must be a Kotlin class"
        }
        // For the smart cast
        if (type !is KSClassDeclaration) return@forEach

        val jsonClassAnnotation = type.findAnnotationWithType(jsonClassType) ?: return@forEach

        val generator = jsonClassAnnotation.getMember<String>("generator")

        if (generator.isNotEmpty()) return@forEach

        if (!jsonClassAnnotation.getMember<Boolean>("generateAdapter")) return@forEach

        val originatingFile = type.containingFile!!
        val adapterGenerator = adapterGenerator(logger, resolver, type) ?: return emptyList()
        try {
          val preparedAdapter = adapterGenerator
            .prepare(generatedProguard) { spec ->
              spec.toBuilder()
                .apply {
                  generatedAnnotation?.let(::addAnnotation)
                }
                .addOriginatingKSFile(originatingFile)
                .build()
            }
          preparedAdapter.spec.writeTo(codeGenerator)
          if (generatedProguard) {
            preparedAdapter.proguardConfig?.writeTo(codeGenerator, originatingFile)
          } else {
            logger.warn("Moshi will not generate Proguard rule." +
                    " obfuscation will break your application" +
                    " unless having your own JsonAdapter look-up tool")
          }
        } catch (e: Exception) {
          logger.error(
            "Error preparing ${type.simpleName.asString()}: ${e.stackTrace.joinToString("\n")}"
          )
        }
      }
    return emptyList()
  }

  private fun ProguardConfig.writeTo(codeGenerator: CodeGenerator, originatingKSFile: KSFile) {
    // TODO outputFile needs to be public
    val name = "META-INF/proguard/moshi-${targetClass.canonicalName}"
    val file = codeGenerator.createNewFile(
      dependencies = Dependencies(aggregating = false, originatingKSFile),
      packageName = "",
      fileName = name,
      extensionName = "pro"
    )
    // Don't use writeTo(file) because that tries to handle directories under the hood
    OutputStreamWriter(file, StandardCharsets.UTF_8)
      .use {
        // TODO writeTo(Appendable) needs to be public
        ProguardConfig::class.java.getDeclaredMethod("writeTo", Appendable::class.java)
          .apply {
            isAccessible = true
          }
          .invoke(this, it)
      }
  }

  private fun adapterGenerator(
    logger: KSPLogger,
    resolver: Resolver,
    originalType: KSClassDeclaration,
  ): AdapterGenerator? {
    val type = targetType(originalType, resolver, logger) ?: return null

    val properties = mutableMapOf<String, PropertyGenerator>()
    for (property in type.properties.values) {
      val generator = property.generator(logger, resolver, originalType)
      if (generator != null) {
        properties[property.name] = generator
      }
    }

    for ((name, parameter) in type.constructor.parameters) {
      if (type.properties[parameter.name] == null && !parameter.hasDefault) {
        // TODO would be nice if we could pass the parameter node directly?
        logger.error("No property for required constructor parameter $name", originalType)
        return null
      }
    }

    // Sort properties so that those with constructor parameters come first.
    val sortedProperties = properties.values.sortedBy {
      if (it.hasConstructorParameter) {
        it.target.parameterIndex
      } else {
        Integer.MAX_VALUE
      }
    }

    return AdapterGenerator(type, sortedProperties)
  }
}
