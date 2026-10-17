# Task 61 — UX-06: Fix accessibility roles and states

**Wave 4 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) medium-a11y-resize · medium-a11y-advanced

## Problem
The resize row is a double TalkBack target: the `Row` carries `clickable` (`UploadScreen.kt:424-441`) while the inner `Checkbox` is separately actionable, so TalkBack announces an unlabelled checkbox and the row has no checked state. The "Advanced credentials" disclosure (`LoginScreen.kt:122-133`) is a clickable `Text` whose only affordance is a `▲`/`▼` glyph — no button role, no expanded/collapsed state.

## Scope
- `UploadScreen.kt:424-441`: make the row the single toggle — `.toggleable(value = resizeTo8Mb, role = Role.Checkbox, onValueChange = onResizeToggled)` and `Checkbox(checked = resizeTo8Mb, onCheckedChange = null)`.
- `LoginScreen.kt:122-133`: replace the clickable `Row`/`Text` with a real control (`TextButton`) or add `role` + `stateDescription` ("Expanded"/"Collapsed"), and drop the glyph-only label from the accessible text.
- Reuse the existing string wording where possible so UX-07's extraction is a no-op for these labels.

```kotlin
Row(
    modifier = Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .toggleable(value = resizeTo8Mb, role = Role.Checkbox, onValueChange = onResizeToggled)
        .padding(vertical = 4.dp)
) {
    Checkbox(checked = resizeTo8Mb, onCheckedChange = null)
    Text("Resize down to 8MB", style = MaterialTheme.typography.bodyMedium)
}
```

## Acceptance criteria
- [ ] TalkBack announces the resize option once, as a checkbox, with its checked state.
- [ ] The advanced-credentials toggle is announced as a button/switch with an expanded or collapsed state, not as "▲"/"▼".
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles.

## Dependencies
- None.

## Verification
- `./gradlew :app:assembleLocalDevDebug`, then manual TalkBack pass over the Login and Upload screens.
