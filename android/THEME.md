# Herdr appearance

Designed with Claude as a read-only designer/reviewer; implemented with native
Material 3 roles. The app uses its own palette, not wallpaper-derived colors.
System, Light and Dark remain available in App settings.

| Role | Light | Dark |
|---|---|---|
| App surface | `#F7F8FA` | `#11151B` |
| Header / pane bar | `#EBEEF2` | `#1A2029` |
| Drawer | `#F1F3F6` | `#151A21` |
| Cards / dialogs / input | `#E4E8ED` | `#212833` |
| Main text | `#1A202B` | `#E0E5ED` |
| Secondary text | `#4A5466` | `#A9B2C0` |
| Navigation / selected pane | `#1A5BD6` | `#8AB4F8` |
| Secondary accent | `#0B6E73` | `#86D7CC` |
| Worktree labels | `#6A3BBF` | `#C8B0F0` |
| Error / needs input | `#BA1A3C` | `#F28B9E` |

All roles, including fixed colors, inverse surfaces, containers and outlines,
are explicit in `HerdrTheme.kt`. Elevation uses a neutral surface tint.
Terminal cells and images keep the colors supplied by the server. The Latest
button uses a separate light-on-dark palette over terminal content.

## Agent states

| State | Light | Dark | Indicator |
|---|---|---|---|
| Working | `#965A00` | `#F2CD6F` | Solid dot |
| Needs input | `#BA1A3C` | `#F28B9E` | Dot with outer ring |
| Done, unseen | `#0B6E73` | `#86D7CC` | Thick ring |
| Idle | `#2F7D32` | `#9BD58F` | Solid dot |
| Unknown / shell | `#727C8C` | `#7D8798` | Thin ring |

State text stays neutral. Shape and text distinguish states without relying
only on color. TalkBack announces the state where no visible status label exists.

## Shape and density

Corner sizes: 4 / 8 / 12 / 16 / 20 dp. System Roboto; workspace title 15 sp medium;
terminal remains monospace. Header and pane bar have a 48 dp minimum height
and grow with text. Spacing follows 4 / 8 / 12 / 16 / 24 dp; action rows start at
56 dp, and primary touch targets reserve at least 48 dp.

## Verification

- Text/background color pairs checked at 4.5:1 or better, including raised surfaces.
- Status indicators checked at 3:1 or better; lowest measured ratio is 3.24:1.
- Light and Dark checked in the visible Android emulator, including system icons
  when the app theme differs from the device theme.
- App UI waits for persisted appearance settings before its first composition.
  The Android-owned launch splash follows the system theme.
- No changes to remote terminal colors or server behavior.

Screenshots: `dist/theme-light.png`, `dist/theme-dark.png`,
`dist/theme-dark-drawer.png`. Contrast report: `dist/theme-contrast.txt`.

## Interaction system

`DesignSystem.kt` owns adaptive form dialogs, action sheets, section labels, choice rows, whole-row
switches and confirmations, and error banners. `HerdrTheme.kt` owns colors,
shape and typography roles. All controls use native Compose / Material 3; no
additional UI dependency or remote asset is required.

- **Context:** workspace and server remain visible above the terminal. Tabs are
  discoverable even when there is only one. At large text sizes, the tab label
  moves into the context line and its picker becomes an icon button.
- **Action sheets:** persistent title and object context, grouped rows, distinct
  destructive actions, explicit dismiss, and a stable-height search for long
  menus. Selected rows have a check mark and tonal background. A disabled action
  explains why it is unavailable when context alone is insufficient.
- **Panes:** one focused terminal on a phone; a layout overview and a list view.
  Small tiles and large text default to the list. Each pane has its own actions.
  Add pane asks for split direction. Resize also has slider / direction controls
  and accessibility actions. Stale layout frames cannot supply draggable splits.
- **Scope:** selecting a pane on this phone differs from shared server zoom.
  Overview actions carry the explicit pane ID. Dialogs freeze server selection
  while editing, show the target, and explain the impact of destructive actions.
- **Delivery:** a busy action cannot be submitted twice. After an uncertain
  response, another action requires an explicit acknowledgement that the server
  was checked. Destructive confirmation resets as well. No automatic retry.
- **Forms:** full-width fields, appropriate SSH keyboards, scrollable content,
  fixed action buttons, collapsed advanced options, and visible validation errors.
  Below 320 dp of available height the whole form scrolls, keeping every field
  and action reachable with a landscape keyboard. Window insets include the IME.
  Dialog system-bar icons follow the app theme even when it differs from Android.
  Credentials retain secure-window protection and per-use biometric authorization.
- **History:** separate search navigation, match count, copy menu and pagination.
  Live terminal input stays reachable. Touch scrolling does not depend on a mouse.
- **Settings:** appearance, terminal preview, notifications, security and optional
  gesture help. Technical details appear where they affect a decision.

## Reference study, 2026-10-31

Reviewed with Claude, acting as a read-only designer and reviewer. These are
selected references, not a claim of an objective ranking. The Behance project
cover visuals were inspected through their public embeds; the complete case
studies were not available through the page reader.

| Reference | Useful principle | Herdr adaptation |
|---|---|---|
| [Oryxen Server Monitoring Mobile App — Vektora, Dribbble](https://dribbble.com/shots/27789880-Oryxen-Server-Monitoring-Mobile-App) | Calm surfaces, status labels, grouped operations | Neutral app surfaces and compact agent rows; no oversized gauges or floating navigation |
| [Xcore Mobile Dashboard — Behance](https://www.behance.net/gallery/238160051/Xcore-Mobile-Dashboard-UIUX-Design) | Distinct alert state and consistent surface hierarchy | Error containers with recovery actions; preserve terminal colors |
| [Server hosting control panel — Behance](https://www.behance.net/gallery/227254867/Server-hosting-control-panel-UI-UX-Design) | Navigation sections and repeated contextual rows | Group actions by tab, workspace, worktree, input and layout |
| [Cloud Server Dashboard — Ali Usama, Behance](https://www.behance.net/gallery/179653743/Cloud-Server-Dashboard-UI-Design) | Clear current selection and create action within navigation | Persistent tab picker, selected pane state, workspace creation in the drawer |

Android interaction details follow the [Compose accessibility guidance](https://developer.android.com/develop/ui/compose/accessibility/api-defaults):
minimum touch targets, native control semantics and selectable / toggleable rows.
Artwork and fonts from these portfolio examples are not bundled in the app.

## UI polish verification

- 36 JVM tests passed; Android lint reports no errors (existing SDK/library and
  platform warnings remain).
- The final integration run passed 11 Android scenarios; two opt-in device checks
  were skipped because their separate biometric/SSH fixtures were not supplied.
- Live SSH scenarios exercise independent servers with overlapping pane IDs,
  tab creation, splitting/resizing, pane selection, keyboard geometry, searchable
  scrollback, copying and image delivery. Disposable server sessions only.
- UI contracts cover single-tab discovery, contextual disabled actions, searching
  actions, per-pane targeting, frozen destructive targets and uncertain delivery.
- Large-text forms were checked with Android system font scale 200%, including
  a landscape keyboard. Short-window actions are reachable by scrolling.
- Dark-theme system bars were inspected in the production Activity on a light
  Android system, including form dialogs.
- Claude reviewed code and emulator screenshots as a read-only reviewer.
- Manual TalkBack navigation and the current build on physical hardware remain
  separate checks; no physical device was connected during this pass.

Current artifacts are kept outside Git in `dist/design/` and
`dist/herdr-android-debug.apk`. `dist/design/verification.txt` contains the final
runner evidence. Portfolio artwork is not included in these artifacts.
