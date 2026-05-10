package dx.cbpv

enum class EffectSafetyClass {
    Pure,
    Deterministic,
    Async,
    Resource,
    IO,
    JavaMutation,
    Lock,
    Unsafe,
}

data class EffectSafetyRegistry(
    private val classes: Map<EffectName, EffectSafetyClass> = emptyMap(),
) {
    fun withEffect(effect: EffectName, safetyClass: EffectSafetyClass): EffectSafetyRegistry =
        copy(classes = classes + (effect to safetyClass))

    fun safetyClass(effect: EffectName): EffectSafetyClass? =
        classes[effect] ?: primitiveEffectSafety(effect)

    companion object {
        val standard: EffectSafetyRegistry = EffectSafetyRegistry()
            .withEffect("Pure", EffectSafetyClass.Pure)
            .withEffect("Shape", EffectSafetyClass.Deterministic)
            .withEffect("Random", EffectSafetyClass.Deterministic)
            .withEffect("TensorOps", EffectSafetyClass.Deterministic)
            .withEffect("Diff", EffectSafetyClass.Deterministic)
            .withEffect("Async", EffectSafetyClass.Async)
            .withEffect("Resource", EffectSafetyClass.Resource)
            .withEffect("IO", EffectSafetyClass.IO)
            .withEffect("JavaMutation", EffectSafetyClass.JavaMutation)
            .withEffect("Lock", EffectSafetyClass.Lock)
            .withEffect("Unsafe", EffectSafetyClass.Unsafe)
    }
}

private fun primitiveEffectSafety(effect: EffectName): EffectSafetyClass? =
    when {
        effect.startsWith("Throws[") && effect.endsWith("]") -> EffectSafetyClass.IO
        else -> null
    }

data class EffectPolicy(
    val name: String,
    val allowedClasses: Set<EffectSafetyClass>,
    val explicitlyAllowedEffects: Set<EffectName> = emptySet(),
    val explicitlyRejectedEffects: Set<EffectName> = emptySet(),
) {
    fun allows(effect: EffectName, safetyClass: EffectSafetyClass): Boolean =
        effect in explicitlyAllowedEffects ||
            (effect !in explicitlyRejectedEffects && safetyClass in allowedClasses)

    companion object {
        val PureDeclaration = EffectPolicy(
            name = "pure declaration",
            allowedClasses = setOf(EffectSafetyClass.Pure),
        )

        val GradRegion = EffectPolicy(
            name = "grad region",
            allowedClasses = setOf(
                EffectSafetyClass.Pure,
                EffectSafetyClass.Deterministic,
            ),
            explicitlyAllowedEffects = setOf("TensorOps", "Diff", "Shape"),
            explicitlyRejectedEffects = setOf(
                "Async",
                "Resource",
                "IO",
                "JavaMutation",
                "Lock",
                "Unsafe",
            ),
        )

        val FutureMultiShot = EffectPolicy(
            name = "future multi-shot handler",
            allowedClasses = setOf(EffectSafetyClass.Pure),
            explicitlyRejectedEffects = setOf(
                "Async",
                "Resource",
                "IO",
                "JavaMutation",
                "Lock",
                "Unsafe",
            ),
        )

        val AsyncRegion = EffectPolicy(
            name = "async region",
            allowedClasses = setOf(
                EffectSafetyClass.Pure,
                EffectSafetyClass.Deterministic,
                EffectSafetyClass.Async,
                EffectSafetyClass.Resource,
                EffectSafetyClass.IO,
                EffectSafetyClass.JavaMutation,
                EffectSafetyClass.Lock,
            ),
            explicitlyRejectedEffects = setOf("Unsafe"),
        )

        val TopLevelScript = EffectPolicy(
            name = "top-level script",
            allowedClasses = setOf(
                EffectSafetyClass.Pure,
                EffectSafetyClass.Deterministic,
                EffectSafetyClass.Async,
                EffectSafetyClass.Resource,
                EffectSafetyClass.IO,
                EffectSafetyClass.JavaMutation,
                EffectSafetyClass.Lock,
            ),
            explicitlyRejectedEffects = setOf("Unsafe"),
        )

        val UnsafeBlock = EffectPolicy(
            name = "unsafe block",
            allowedClasses = EffectSafetyClass.entries.toSet(),
        )
    }
}

sealed interface EffectPolicyDiagnostic {
    data class UnknownEffectSafety(val effect: EffectName) : EffectPolicyDiagnostic
    data class RejectedEffect(
        val policy: String,
        val effect: EffectName,
        val safetyClass: EffectSafetyClass,
    ) : EffectPolicyDiagnostic
}

data class EffectPolicyReport(
    val policy: EffectPolicy,
    val diagnostics: List<EffectPolicyDiagnostic>,
) {
    val isAccepted: Boolean get() = diagnostics.isEmpty()
}

class EffectPolicyChecker(
    private val registry: EffectSafetyRegistry = EffectSafetyRegistry.standard,
) {
    fun check(type: ComputationType, policy: EffectPolicy): EffectPolicyReport {
        val diagnostics = type.allEffects()
            .sorted()
            .mapNotNull { effect ->
                val safetyClass = registry.safetyClass(effect)
                    ?: return@mapNotNull EffectPolicyDiagnostic.UnknownEffectSafety(effect)

                if (policy.allows(effect, safetyClass)) {
                    null
                } else {
                    EffectPolicyDiagnostic.RejectedEffect(policy.name, effect, safetyClass)
                }
            }

        return EffectPolicyReport(policy, diagnostics)
    }

    fun check(result: TypeCheckResult, policy: EffectPolicy): EffectPolicyReport {
        val type = result.type
        return if (type == null) {
            EffectPolicyReport(policy, emptyList())
        } else {
            check(type, policy)
        }
    }
}
