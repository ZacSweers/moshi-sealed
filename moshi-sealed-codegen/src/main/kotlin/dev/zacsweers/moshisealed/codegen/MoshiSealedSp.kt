package dev.zacsweers.moshisealed.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import org.jetbrains.kotlin.ksp.processing.CodeGenerator
import org.jetbrains.kotlin.ksp.processing.Resolver
import org.jetbrains.kotlin.ksp.processing.SymbolProcessor

class MoshiSealedSp : SymbolProcessor {

  companion object {
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
    const val OPTION_GENERATED = "moshi.generated"
    private val POSSIBLE_GENERATED_NAMES = setOf(
        "javax.annotation.processing.Generated",
        "javax.annotation.Generated"
    )
  }

  lateinit var codeGenerator: CodeGenerator
  private var generatedAnnotation: AnnotationSpec? = null

  override fun init(options: Map<String, String>, kotlinVersion: KotlinVersion,
      codeGenerator: CodeGenerator) {
    this.codeGenerator = codeGenerator

    generatedAnnotation = options[OPTION_GENERATED]?.let {
      require(it in POSSIBLE_GENERATED_NAMES) {
        "Invalid option value for $OPTION_GENERATED. Found $it, allowable values are $POSSIBLE_GENERATED_NAMES."
      }
      ClassName.bestGuess(it)
    }?.let {
      AnnotationSpec.builder(it)
          .addMember("value = [%S]", MoshiSealedSp::class.java.canonicalName)
          .addMember("comments = %S", "https://github.com/ZacSweers/moshi-sealed")
          .build()
    }
  }

  override fun process(resolver: Resolver) {

  }

  override fun finish() {

  }
}