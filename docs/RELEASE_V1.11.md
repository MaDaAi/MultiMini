## Plain English
- MultiMini has a whole new look: calmer colours, clearer text, rounded cards and smoother buttons, in both dark and light mode.
- Picking a window is now visual: you see a live preview of every open window (and every monitor) and click the one you want, instead of choosing from a text list.
- Your sources show small live previews in the list, and the selected source shows the app's icon (Windows) or its capture type.
- Clearer icons, a tidier Settings window and a nicer screenshot Gallery.
- Multitasking itself works exactly the same.

## Detailed
- New visual system: layered colour palette, Segoe UI Semibold headings on Windows (system fonts on Linux/macOS), refined buttons with hover fades and focus rings, simpler sliders, rounded inputs and dropdowns, thinner scrollbars and menus.
- Window picker for adding and configuring window/game captures: searchable grid with previews (Windows PrintWindow, Linux X11/XWayland shared memory, macOS CoreGraphics or ScreenCaptureKit). Native Wayland windows show a placeholder and still use the desktop picker.
- Display picker with a snapshot of each monitor (not available on Wayland).
- Source list rows with live mini thumbnails and type colours; selected-source header with the program icon on Windows or the capture-type logo.
- Configure-mode preview stage with rounded corners and clearer placeholders; multitask mode drawing is unchanged.
- Settings navigation with icons; Gallery cards with a "Kept" badge; Add source cards with descriptions.
- Fixes: dropdown text no longer turns black when focused; switching between light and dark no longer leaves dark areas; the right panel keeps a normal width when the window is resized.
