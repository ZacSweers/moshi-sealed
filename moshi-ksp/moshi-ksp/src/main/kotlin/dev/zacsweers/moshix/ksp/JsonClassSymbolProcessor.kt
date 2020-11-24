package dev.zacsweers.moshix.ksp

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Origin.KOTLIN
import dev.zacsweers.moshix.ksp.shade.api.BridgeGenerator
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.moshi.JsonClass
import dev.zacsweers.moshix.ksp.shade.api.AdapterGenerator
import dev.zacsweers.moshix.ksp.shade.api.ProguardConfig
import dev.zacsweers.moshix.ksp.shade.api.PropertyGenerator
import org.jetbrains.kotlin.analyzer.AnalysisResult.CompilationErrorException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

@AutoService(SymbolProcessor::class)
public class JsonClassSymbolProcessor : SymbolProcessor {

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

    private val POSSIBLE_GENERATED_NAMES = setOf(
      "javax.annotation.processing.Generated",
      "javax.annotation.Generated"
    )

    private val JSON_CLASS_NAME = JsonClass::class.qualifiedName!!
  }

  private lateinit var codeGenerator: CodeGenerator
  private lateinit var logger: MoshiKSPLogger
  private var generatedOption: String? = null

  override fun init(
    options: Map<String, String>,
    kotlinVersion: KotlinVersion,
    codeGenerator: CodeGenerator,
    logger: KSPLogger,
  ) {
    this.codeGenerator = codeGenerator
    this.logger = MoshiKSPLogger(logger)

    generatedOption = options[OPTION_GENERATED]?.also {
      logger.check(it in POSSIBLE_GENERATED_NAMES) {
        "Invalid option value for $OPTION_GENERATED. Found $it, allowable values are $POSSIBLE_GENERATED_NAMES."
      }
    }
  }

  override fun process(resolver: Resolver) {
    val generatedAnnotation = generatedOption?.let {
      val annotationType = resolver.getClassDeclarationByName(resolver.getKSNameFromString(it))
        ?: error("Generated annotation type doesn't exist: $it")
      AnnotationSpec.builder(annotationType.toClassName())
        .addMember("value = [%S]", JsonClassSymbolProcessor::class.java.canonicalName)
        .addMember("comments = %S", "https://github.com/square/moshi")
        .build()
    }

    val jsonClassType = resolver.getClassDeclarationByName(
      resolver.getKSNameFromString(JSON_CLASS_NAME))
      ?.asType()
      ?: error("JsonClass type not found on the classpath.")
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

        val adapterGenerator = adapterGenerator(logger, resolver, type)
        try {
          val preparedAdapter = adapterGenerator
            .prepare { spec ->
              spec.toBuilder()
                .apply {
                  generatedAnnotation?.let(::addAnnotation)
                }
                .build()
            }
          preparedAdapter.spec.writeTo(codeGenerator)
          preparedAdapter.proguardConfig?.writeTo(codeGenerator)
          preparedAdapter.bridgeType?.writeTo(codeGenerator)
        } catch (e: Exception) {
          logger.error(
            "Error preparing ${type.simpleName.asString()}: ${e.stackTrace.joinToString("\n")}")
        }
      }
  }

  override fun finish() {
    logger.reportErrors()
  }

  private fun ProguardConfig.writeTo(codeGenerator: CodeGenerator) {
    // TODO outputFile needs to be public
    val name = "META-INF/proguard/moshi-${targetClass.canonicalName}"
    val file = codeGenerator.createNewFile("", name, "pro")
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

  private fun BridgeGenerator.writeTo(codeGenerator: CodeGenerator) {
    val classBytes = generateClassBytes()
    codeGenerator.createNewFile(packageName, name, "class").buffered().use {
      it.write(classBytes)
    }
  }

  private fun adapterGenerator(
    logger: KSPLogger,
    resolver: Resolver,
    originalType: KSClassDeclaration,
  ): AdapterGenerator {
    val type = targetType(originalType, resolver, logger)

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
        logger.errorAndThrow("No property for required constructor parameter $name", originalType)
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

    return AdapterGenerator(type, sortedProperties, generateBytecode = true)
  }
}

// TODO temporary until KSP's logger makes errors fail the build
private class MoshiKSPLogger(private val delegate: KSPLogger) : KSPLogger by delegate {
  private val hasErrors = AtomicBoolean(false)
  override fun error(message: String, symbol: KSNode?) {
    delegate.error(message, symbol)
    hasErrors.set(true)
  }

  fun reportErrors() {
    if (hasErrors.get()) {
      throw CompilationErrorException()
    }
  }
}