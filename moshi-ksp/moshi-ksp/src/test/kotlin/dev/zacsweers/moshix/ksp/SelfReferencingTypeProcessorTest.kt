package dev.zacsweers.moshix.ksp

import com.google.common.truth.Truth
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspIncremental
import com.tschuchort.compiletesting.symbolProcessorProviders
import dev.zacsweers.moshix.ksp.shade.api.rawType
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

typealias TestCallback = (SymbolProcessorEnvironment, Resolver) -> Unit
class SelfReferencingTypeProcessorTest {
    @Rule
    @JvmField var temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun selfReferencing() {
        val src = SourceFile.kotlin("foo.kt",
        """
            class SelfReferencing<T : SelfReferencing<T>>
        """.trimIndent())
        compile(src) { env, resolver ->
            val rawTypeName = ClassName("", "SelfReferencing")
            val klass = resolver.getClassDeclarationByName("SelfReferencing")
            val paramResolver = klass.typeParameters.toTypeParameterResolver(
                sourceType = klass.qualifiedName?.asString()
            )
            val type = klass.asStarProjectedType()
            val tParam = paramResolver["T"]
            check(tParam is TypeVariableName)
            val firstBound = tParam.bounds.first()
            Truth.assertThat(firstBound.rawType()).isEqualTo(rawTypeName)
            val secondTypeArg = (firstBound as ParameterizedTypeName).typeArguments.first()
            val secondBound = (secondTypeArg as TypeVariableName).bounds.first()
            // this one fails
            Truth.assertThat(secondBound.rawType()).isEqualTo(rawTypeName)
            // Line below recorses indefinitely
            // val typeName = type.toTypeName(paramResolver)
        }
    }

    class TestProvider(
        private val testProcess : TestCallback
    ) : SymbolProcessorProvider {
        var exception: Throwable? = null
        override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
            return object : SymbolProcessor {
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    try {
                        testProcess(environment, resolver)
                    }catch (th:Throwable) {
                        exception = th
                    }

                    return emptyList()
                }
            }
        }

    }
    private fun compile(vararg sourceFiles: SourceFile,
                        testProcess : TestCallback
    ) {
        val provider = TestProvider(testProcess)
        val compilation =  KotlinCompilation()
            .apply {
                workingDir = temporaryFolder.root
                inheritClassPath = true
                symbolProcessorProviders = listOf(provider)
                sources = sourceFiles.asList()
                verbose = false
                kspIncremental = false
            }.compile()
        provider.exception?.let { throw it }
    }
}