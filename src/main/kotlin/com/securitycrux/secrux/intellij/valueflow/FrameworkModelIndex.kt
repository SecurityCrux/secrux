package com.securitycrux.secrux.intellij.valueflow

enum class BeanKind {
    STEREOTYPE,
    BEAN_METHOD,
}

data class BeanDefinition(
    val typeFqn: String,
    val kind: BeanKind,
    val source: String?,
    val filePath: String?,
    val startOffset: Int?,
)

enum class InjectionKind {
    FIELD,
    CONSTRUCTOR_PARAM,
    METHOD_PARAM,
}

data class InjectionPoint(
    val ownerClassFqn: String,
    val kind: InjectionKind,
    val targetTypeFqn: String,
    val name: String?,
    val filePath: String?,
    val startOffset: Int?,
)

data class FrameworkModelStats(
    val beans: Int,
    val injections: Int,
    val classesWithBeans: Int,
    val classesWithInjections: Int,
)

data class FrameworkModelIndex(
    val beans: List<BeanDefinition>,
    val injections: List<InjectionPoint>,
    val stats: FrameworkModelStats,
)

