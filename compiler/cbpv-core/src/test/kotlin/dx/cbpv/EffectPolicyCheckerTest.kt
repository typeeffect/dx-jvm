package dx.cbpv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EffectPolicyCheckerTest {
    private val checker = EffectPolicyChecker()

    @Test
    fun pureDeclarationAcceptsPureComputations() {
        val report = checker.check(
            ComputationType(ValueType.IntType, emptySet()),
            EffectPolicy.PureDeclaration,
        )

        assertTrue(report.isAccepted, "${report.diagnostics}")
    }

    @Test
    fun pureDeclarationRejectsIO() {
        val report = checker.check(
            ComputationType(ValueType.UnitType, setOf("IO")),
            EffectPolicy.PureDeclaration,
        )

        assertEquals(
            listOf(
                EffectPolicyDiagnostic.RejectedEffect(
                    policy = "pure declaration",
                    effect = "IO",
                    safetyClass = EffectSafetyClass.IO,
                ),
            ),
            report.diagnostics,
        )
    }

    @Test
    fun gradRegionAcceptsTensorDiffShape() {
        val report = checker.check(
            ComputationType(ValueType.IntType, setOf("TensorOps", "Diff", "Shape")),
            EffectPolicy.GradRegion,
        )

        assertTrue(report.isAccepted, "${report.diagnostics}")
    }

    @Test
    fun gradRegionRejectsAsyncResourceIOJavaMutationLockUnsafe() {
        val report = checker.check(
            ComputationType(
                ValueType.UnitType,
                setOf("Async", "Resource", "IO", "JavaMutation", "Lock", "Unsafe"),
            ),
            EffectPolicy.GradRegion,
        )

        assertEquals(
            listOf(
                EffectPolicyDiagnostic.RejectedEffect("grad region", "Async", EffectSafetyClass.Async),
                EffectPolicyDiagnostic.RejectedEffect("grad region", "IO", EffectSafetyClass.IO),
                EffectPolicyDiagnostic.RejectedEffect("grad region", "JavaMutation", EffectSafetyClass.JavaMutation),
                EffectPolicyDiagnostic.RejectedEffect("grad region", "Lock", EffectSafetyClass.Lock),
                EffectPolicyDiagnostic.RejectedEffect("grad region", "Resource", EffectSafetyClass.Resource),
                EffectPolicyDiagnostic.RejectedEffect("grad region", "Unsafe", EffectSafetyClass.Unsafe),
            ),
            report.diagnostics,
        )
    }

    @Test
    fun asyncRegionAcceptsAsyncResourceIOJavaMutationLock() {
        val report = checker.check(
            ComputationType(
                ValueType.UnitType,
                setOf("Async", "Resource", "IO", "JavaMutation", "Lock"),
            ),
            EffectPolicy.AsyncRegion,
        )

        assertTrue(report.isAccepted, "${report.diagnostics}")
    }

    @Test
    fun asyncRegionRejectsUnsafeByDefault() {
        val report = checker.check(
            ComputationType(ValueType.UnitType, setOf("Unsafe")),
            EffectPolicy.AsyncRegion,
        )

        assertEquals(
            listOf(EffectPolicyDiagnostic.RejectedEffect("async region", "Unsafe", EffectSafetyClass.Unsafe)),
            report.diagnostics,
        )
    }

    @Test
    fun topLevelScriptAllowsJavaMutationButRejectsUnsafe() {
        val allowed = checker.check(
            ComputationType(ValueType.UnitType, setOf("JavaMutation")),
            EffectPolicy.TopLevelScript,
        )
        val rejected = checker.check(
            ComputationType(ValueType.UnitType, setOf("Unsafe")),
            EffectPolicy.TopLevelScript,
        )

        assertTrue(allowed.isAccepted, "${allowed.diagnostics}")
        assertEquals(
            listOf(EffectPolicyDiagnostic.RejectedEffect("top-level script", "Unsafe", EffectSafetyClass.Unsafe)),
            rejected.diagnostics,
        )
    }

    @Test
    fun futureMultiShotAcceptsOnlyPureEffects() {
        val accepted = checker.check(
            ComputationType(ValueType.IntType, emptySet()),
            EffectPolicy.FutureMultiShot,
        )
        val rejected = checker.check(
            ComputationType(ValueType.IntType, setOf("Async")),
            EffectPolicy.FutureMultiShot,
        )

        assertTrue(accepted.isAccepted, "${accepted.diagnostics}")
        assertEquals(
            listOf(
                EffectPolicyDiagnostic.RejectedEffect(
                    "future multi-shot handler",
                    "Async",
                    EffectSafetyClass.Async,
                ),
            ),
            rejected.diagnostics,
        )
    }

    @Test
    fun throwsEffectIsTreatedAsIO() {
        val report = checker.check(
            ComputationType(ValueType.UnitType, setOf("Throws[IOException]")),
            EffectPolicy.GradRegion,
        )

        assertEquals(
            listOf(
                EffectPolicyDiagnostic.RejectedEffect(
                    "grad region",
                    "Throws[IOException]",
                    EffectSafetyClass.IO,
                ),
            ),
            report.diagnostics,
        )
    }

    @Test
    fun unknownEffectSafetyIsDiagnostic() {
        val report = checker.check(
            ComputationType(ValueType.UnitType, setOf("Http")),
            EffectPolicy.GradRegion,
        )

        assertEquals(listOf(EffectPolicyDiagnostic.UnknownEffectSafety("Http")), report.diagnostics)
    }

    @Test
    fun customRegistryCanClassifyDomainEffects() {
        val registry = EffectSafetyRegistry.standard
            .withEffect("Http", EffectSafetyClass.IO)
            .withEffect("SeededRandom", EffectSafetyClass.Deterministic)
        val customChecker = EffectPolicyChecker(registry)

        val http = customChecker.check(
            ComputationType(ValueType.UnitType, setOf("Http")),
            EffectPolicy.GradRegion,
        )
        val seeded = customChecker.check(
            ComputationType(ValueType.IntType, setOf("SeededRandom")),
            EffectPolicy.GradRegion,
        )

        assertFalse(http.isAccepted)
        assertTrue(seeded.isAccepted, "${seeded.diagnostics}")
    }

    @Test
    fun unsafeBlockAcceptsEverythingWithKnownSafety() {
        val report = checker.check(
            ComputationType(
                ValueType.UnitType,
                setOf("Async", "Resource", "IO", "JavaMutation", "Lock", "Unsafe"),
            ),
            EffectPolicy.UnsafeBlock,
        )

        assertTrue(report.isAccepted, "${report.diagnostics}")
    }
}
