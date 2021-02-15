package dev.zacsweers.moshix.sealed.codegen.ksp

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.impl.MessageCollectorBasedKSPLogger
import com.google.devtools.ksp.symbol.ClassKind.OBJECT
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclarationContainer
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.asClassName
import com.squareup.moshi.JsonClass
import dev.zacsweers.moshix.sealed.annotations.DefaultNull
import dev.zacsweers.moshix.sealed.annotations.DefaultObject
import dev.zacsweers.moshix.sealed.annotations.TypeLabel
import dev.zacsweers.moshix.sealed.runtime.internal.ObjectJsonAdapter
import org.jetbrains.kotlin.analyzer.AnalysisResult.CompilationErrorException

@AutoService(SymbolProcessor::class)
public class MoshiSealedSymbolProcessor : SymbolProcessor {

  public companion object {
    /**
     * This annotation processing argument can be specified to have a `@Generated` annotation
     * included in the generated code. It is not encouraged unless you need it for static analysis
     * reasons and not enabled by default.
     *
     * Note that this can only be one of the following values:
     *   * `"javax.annotation.processing.Generated"` (JRE 9+)
     *   * `"javax.annotation.Generated"` (JRE <9)
     *
     * We reuse Moshi's option for convenience so you don't have to declare multiple options.
     */
    public const val OPTION_GENERATED: String = "moshi.generated"

    private val POSSIBLE_GENERATED_NAMES = setOf(
      "javax.annotation.processing.Generated",
      "javax.annotation.Generated"
    )

    private val JSON_CLASS_NAME = JsonClass::class.qualifiedName!!
  }

  private lateinit var codeGenerator: CodeGenerator
  private lateinit var logger: KSPLogger
  private var generatedOption: String? = null

  override fun init(
    options: Map<String, String>,
    kotlinVersion: KotlinVersion,
    codeGenerator: CodeGenerator,
    logger: KSPLogger,
  ) {
    this.codeGenerator = codeGenerator
    this.logger = logger

    generatedOption = options[OPTION_GENERATED]?.also {
      require(it in POSSIBLE_GENERATED_NAMES) {
        "Invalid option value for $OPTION_GENERATED. Found $it, allowable values are $POSSIBLE_GENERATED_NAMES."
      }
    }
  }

  override fun process(resolver: Resolver): List<KSAnnotated> {
    val generatedAnnotation = generatedOption?.let {
      val annotationType = resolver.getClassDeclarationByName(resolver.getKSNameFromString(it))
        ?: run {
          logger.error("Generated annotation type doesn't exist: $it")
          return emptyList()
        }
      AnnotationSpec.builder(annotationType.toClassName())
        .addMember("value = [%S]", MoshiSealedSymbolProcessor::class.java.canonicalName)
        .addMember("comments = %S", "https://github.com/ZacSweers/moshi-sealed")
        .build()
    }

    val jsonClassType = resolver.getClassDeclarationByName(
      resolver.getKSNameFromString(JSON_CLASS_NAME))
      ?.asType()
      ?: run {
        logger.error("JsonClass type not found on the classpath.")
        return emptyList()
      }
    resolver.getSymbolsWithAnnotation(JSON_CLASS_NAME)
      .asSequence()
      .forEach { type ->
        check(type is KSClassDeclaration) {
          "@JsonClass is only applicable to classes!"
        }

        val jsonClass = type.findAnnotationWithType(jsonClassType) ?: return@forEach
        if (!jsonClass.getMember<Boolean>("generateAdapter")) return@forEach

        val generator = jsonClass.getMember<String>("generator")

        if (!generator.startsWith("sealed:")) {
          return@forEach
        }

        check(Modifier.SEALED in type.modifiers) {
          "Must be a sealed class!"
        }

        val typeLabel = generator.removePrefix("sealed:")
        createType(resolver, type, typeLabel, generatedAnnotation)
      }
    return emptyList()
  }

  private fun createType(
    resolver: Resolver,
    type: KSClassDeclaration,
    typeLabel: String,
    generatedAnnotation: AnnotationSpec?,
  ) {
    val defaultNullAnnotation = resolver.getClassDeclarationByName<DefaultNull>().asType()
    val defaultObjectAnnotation = resolver.getClassDeclarationByName<DefaultObject>().asType()
    val typeLabelAnnotation = resolver.getClassDeclarationByName<TypeLabel>().asType()
    val useDefaultNull = type.hasAnnotation(defaultNullAnnotation)
    val objectAdapters = mutableListOf<CodeBlock>()
    val seenLabels = mutableMapOf<String, ClassName>()
    val sealedSubtypes = type.sealedSubtypes()
      .mapTo(LinkedHashSet()) { subtype ->
        val className = subtype.toClassName()
        val isObject = subtype.classKind == OBJECT
        if (isObject && subtype.hasAnnotation(defaultObjectAnnotation)) {
          if (useDefaultNull) {
            // Print both for reference
            logger.error("""
                Cannot have both @DefaultNull and @DefaultObject. @DefaultObject type: $type
                Cannot have both @DefaultNull and @DefaultObject. @DefaultNull type: $subtype
              """.trimIndent(), subtype)
            return
          } else {
            return@mapTo Subtype.ObjectType(className)
          }
        } else {
          val labelAnnotation = subtype.findAnnotationWithType(typeLabelAnnotation)
            ?: run {
              logger.error("Missing @TypeLabel", subtype)
              return
            }

          val labels = mutableListOf<String>()

          val mainLabel = labelAnnotation.arguments
            .find { it.name?.getShortName() == "label" }
            ?.value as? String
            ?: run {
              logger.error("No label member for TypeLabel annotation!")
              return
            }

          seenLabels.put(mainLabel, className)?.let { prev ->
            logger.error(
              "Duplicate label '$mainLabel' defined for $className and $prev.",
              type
            )
            return
          }

          labels += mainLabel

          @Suppress("UNCHECKED_CAST")
          val alternates = labelAnnotation.arguments
            .find { it.name?.getShortName() == "alternateLabels" }
            ?.value as? List<String> // arrays are lists in KSP https://github.com/google/ksp/issues/135
            ?: emptyList() // ksp ignores undefined args https://github.com/google/ksp/issues/134

          for (alternate in alternates) {
            seenLabels.put(alternate, className)?.let { prev ->
              logger.error(
                "Duplicate alternate label '$alternate' defined for $className and $prev.",
                type
              )
              return
            }
          }

          labels += alternates

          if (isObject) {
            objectAdapters.add(CodeBlock.of(
              ".%1M<%2T>(%3T(%2T))",
              MemberName("com.squareup.moshi", "addAdapter"),
              className,
              ObjectJsonAdapter::class.asClassName()
            ))
          }
          Subtype.ClassType(className, labels)
        }
      }

    val preparedAdapter = createType(
      targetType = type.toClassName(),
      isInternal = Modifier.INTERNAL in type.modifiers,
      typeLabel = typeLabel,
      useDefaultNull = useDefaultNull,
      generatedAnnotation = generatedAnnotation,
      subtypes = sealedSubtypes,
      objectAdapters = objectAdapters
    ) {
      addOriginatingKSFile(type.containingFile!!)
    }

    val ksFile = preparedAdapter.spec.originatingKSFiles().single()
    preparedAdapter.spec.writeTo(codeGenerator)
    preparedAdapter.proguardConfig.writeTo(codeGenerator, ksFile)
  }

  private fun KSClassDeclaration.sealedSubtypes(): Set<KSClassDeclaration> {
    // All sealed subtypes are guaranteed to to be in this file... somewhere
    val targetSuperClass = asType().declaration
    return containingFile?.allTypes()
      ?.filter { it.superTypes.firstOrNull()?.resolve()?.declaration == targetSuperClass }
      ?.toSet()
      .orEmpty()
  }

  private fun KSDeclarationContainer.allTypes(): Sequence<KSClassDeclaration> {
    val sequence = declarations.asSequence().filterIsInstance<KSClassDeclaration>()
      .flatMap { it.allTypes() }
    return if (this is KSClassDeclaration) {
      sequence + this
    } else {
      sequence
    }
  }

  override fun finish() {

  }

  override fun onError() {
    // TODO temporary until KSP's logger makes errors fail the compilation and not just the build
    (logger as? MessageCollectorBasedKSPLogger)?.reportAll()
    throw CompilationErrorException()
  }
}

internal data class PreparedAdapter(val spec: FileSpec, val proguardConfig: ProguardConfig)