package com.securitycrux.secrux.intellij.sinks

import org.skgroup.codeauditassistant.enums.SubVulnerabilityDefinition
import org.skgroup.codeauditassistant.enums.SubVulnerabilityType

class SinkRegistry(
    definitions: Iterable<SubVulnerabilityDefinition>
) {

    private val methodIndex: Map<String, Map<String, Set<SubVulnerabilityType>>>
    private val constructorIndex: Map<String, Set<SubVulnerabilityType>>

    init {
        val methodMutable = linkedMapOf<String, MutableMap<String, MutableSet<SubVulnerabilityType>>>()
        val ctorMutable = linkedMapOf<String, MutableSet<SubVulnerabilityType>>()

        for (definition in definitions) {
            for ((classFqn, methods) in definition.methodSinks) {
                val byMethod =
                    methodMutable.getOrPut(classFqn) { linkedMapOf() }
                for (methodName in methods) {
                    byMethod.getOrPut(methodName) { linkedSetOf() }.add(definition.subType)
                }
            }

            for (classFqn in definition.constructorSinks) {
                ctorMutable.getOrPut(classFqn) { linkedSetOf() }.add(definition.subType)
            }
        }

        methodIndex = methodMutable.mapValues { (_, v) -> v.mapValues { (_, types) -> types.toSet() } }
        constructorIndex = ctorMutable.mapValues { (_, types) -> types.toSet() }
    }

    fun matchMethodCall(
        classFqn: String,
        methodName: String
    ): Set<SubVulnerabilityType> =
        methodIndex[classFqn]?.get(methodName).orEmpty()

    fun matchConstructorCall(classFqn: String): Set<SubVulnerabilityType> =
        constructorIndex[classFqn].orEmpty()
}

