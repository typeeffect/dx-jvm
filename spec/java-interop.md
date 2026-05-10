# dx Java Interop Spec

Status: draft placeholder.

Scope:

- Classpath model.
- Method and constructor calls.
- Overload resolution.
- Nullability annotations.
- Checked exception effects.
- Unknown Java calls.
- Java-facing exports.

Initial decision:

- Unknown Java calls in top-level scripts are allowed with warnings.
- Unknown Java calls are rejected inside `grad`, future `multi`, and `pure` declarations unless annotated/imported as pure.
