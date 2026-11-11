package com.zeny.desktop.core

import java.lang.classfile.Annotation
import java.lang.classfile.AnnotationValue
import java.lang.classfile.AttributedElement
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val RAW = "Lcom/zeny/desktop/core/RawSdkAccess;"

/**
 * O opt-in `@RawSdkAccess` lido do bytecode: a retenção é `BINARY`, então a reflexão não o vê. O compilador
 * exige o opt-in pelo método sintético `get<Prop>$annotations`; sem a anotação ali, `apis` e `executor` ficariam
 * livres para `app` e `design`.
 */
class RawSdkAccessAttackTest {
    private fun model(type: Class<*>): ClassModel {
        val bytes = type.getResourceAsStream("${type.simpleName}.class").use { assertNotNull(it).readAllBytes() }
        return ClassFile.of().parse(bytes)
    }

    private fun AttributedElement.invisibleAnnotations(): List<Annotation> =
        findAttribute(Attributes.runtimeInvisibleAnnotations()).map { it.annotations() }.orElse(emptyList())

    private fun ClassModel.methodsMarkedRaw(): Set<String> =
        methods()
            .filter { method -> method.invisibleAnnotations().any { it.className().stringValue() == RAW } }
            .map { it.methodName().stringValue() }
            .toSet()

    @Test
    fun onlyApisAndExecutorOfZenyCoreRequireTheOptIn() {
        val marked = model(ZenyCore::class.java).methodsMarkedRaw()

        assertEquals(setOf("getApis\$annotations", "getExecutor\$annotations"), marked)
    }

    @Test
    fun apisAndExecutorStayPublicForTheDemoSeedAndSessionNeedsNoOptIn() {
        for (getter in listOf("getApis", "getExecutor", "getSession", "close")) {
            assertTrue(Modifier.isPublic(ZenyCore::class.java.getMethod(getter).modifiers), "$getter não é público")
        }
    }

    @Test
    fun rawSdkAccessRequiresOptInAtErrorLevelWithTheAdrMessage() {
        val annotations = model(RawSdkAccess::class.java).invisibleAnnotations()
        val requiresOptIn = annotations.firstOrNull { it.className().stringValue() == "Lkotlin/RequiresOptIn;" }
        val found = assertNotNull(requiresOptIn, "RawSdkAccess sem @RequiresOptIn")
        val elements = found.elements().associate { it.name().stringValue() to it.value() }

        val level = elements["level"] as AnnotationValue.OfEnum
        assertEquals("Lkotlin/RequiresOptIn\$Level;", level.className().stringValue())
        assertEquals("ERROR", level.constantName().stringValue())
        val message = elements["message"] as AnnotationValue.OfString
        assertEquals("Acesso cru às operações do sdk: só o core e o demo-seed (ADR 0008).", message.stringValue())
    }
}
