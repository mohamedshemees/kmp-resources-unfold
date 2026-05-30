<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Kmp Resources Unfold Changelog

## [Unreleased]

## [2.2.0]
### Added
- **Hierarchical Density Grouping**: Resources are now grouped by base name with density variations (HDPI, XHDPI, etc.) listed as indented sub-items. Functional parent rows now open the default density version.
- **Native Android & Flutter Support**: 
    - Full support for native Android `res` directories (`src/main/res`) alongside Compose Multiplatform.
    - Flutter project support: automatically lists all root directories as import targets when `pubspec.yaml` is detected.
    - Support for generic `assets/` and `images/` directories project-wide.
- **Import Dialog UX Overhaul**:
    - **Integrated Search**: Searchable ComboBoxes with real-time filtering for module and target selection.
    - **Physical Enter Support**: Pressing Enter confirms selections and refreshes previews without auto-submitting the dialog.
    - **Custom Sub-paths**: Create and target custom sub-directories via dot notation (e.g., `assets.icons.buttons`).
    - **Stability Fix**: Import operations now run on a background thread with progress indicators, preventing IDE freezes during large imports.
- **Multi-Instance Figma Bridge**: 
    - Support for multiple ports (6789-6795) to allow concurrent plugin instances in different projects.
    - Integrated relaunch button and port selection UI directly in the tool window.
- **IDE File Tree Previews**: Tiny thumbnail previews directly in the standard IntelliJ Project View for all supported image and vector formats.
- **Kotlin Resource Linting**: New inspection for `Res.string.key` references in Kotlin code to detect and highlight missing translations in real-time.

### Fixed
- **Module Naming**: Automatic cleaning of redundant `mena.` prefixes from module display names across the UI.
- **Strict Resource Detection**: Refined module filtering to only show valid resource-containing modules in dropdowns, reducing clutter.
- **Focus Persistence**: Fixed an issue where ComboBox text would disappear on focus loss.
- **Inspection Compliance**: Added mandatory descriptions to all lints to satisfy IntelliJ internal standards.

## [2.1.0]
### Added
- **Figma Bridge Integration**: Seamlessly import assets directly from Figma into Android Studio via a local HTTP server (port 6789). Images are processed in-memory and pre-loaded into the Import Dialog. Get the Figma Plugin: [TokenzUnfold](https://www.figma.com/community/plugin/1604528003423822682)

### Fixed
- **Improved SVG to XML Conversion**:
    - Corrected handling of SVG `<mask>` and `<g mask="url(#id)">` elements, converting masks to Android `<clip-path>`.
    - Fixed circle and ellipse path data generation for accurate rendering, matching native Android Studio importer.
    - Enhanced stroke property handling for all shapes (rect, circle, ellipse, path).

## [2.0.0]
### Added
- **Asset Import Overhaul**: Complete workflow for importing SVGs and raster images into KMP modules.
- **SVG to XML Conversion**: Automatic conversion of SVGs to Android Vector Drawables with a user-controlled toggle.
- **Multi-Density Support**: Automatic detection and grouping of density-specific variants (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi) using Figma naming conventions.
- **Manual Density Selection**: Fine-grained control over density buckets for each variant with automatic conflict swapping.
- **Path Previews**: Real-time preview of exact target paths for all assets being imported.
- **Robust Directory Handling**: Automatic creation of `composeResources` and density-specific directories if they are missing.
- **Smart Prefixing**: Optional automatic `ic_` or `img_` prefixing based on file type during import.
- **Checkerboard Backgrounds**: Native-style chessboard background for all icon and image previews for better transparency visibility.

### Changed
- **Unified Import UI**: A professional, consistent multi-step dialog for configuring and reviewing asset imports.
- **Localization**: Migrated hardcoded UI strings to `MyBundle` for better project maintainability and future localization.

### Removed
- **Project Cleanup**: Removed legacy test infrastructure, CI/CD test jobs, and external quality analysis tools (Qodana, Kover) to streamline the build process.

## [1.0.0]
### Added
- **Smart Resource Filtering**: `strings.xml` files are now intelligently hidden from visual asset lists and only appear when the "Strings" filter is active.
- **Enhanced Localization Navigation**: Ctrl + Click on resource keys now shows clear locale labels (e.g., `[ar]`, `[es]`) in the navigation popup.
- **Localization Gutter Markers**: Added editor icons to quickly jump between localized versions of strings and drawables.
- **Missing Translation Inspection**: Real-time linting to highlight keys that are missing translations in sibling locales.

### Changed
- **Platform Support**: Updated minimum requirement to Android Studio Ladybug (2024.2.1+) to leverage modern IDE APIs.

### Fixed
- **Code Optimization**: Eliminated redundant logic across navigation and linting modules by consolidating into a shared processing engine.

## [0.0.3-alpha]
### Fixed
- **API Stability**: Migrated to public `ImageLoader` and `URI` APIs for stable rendering and Marketplace compliance.
- **Editor Lifecycle**: Resolved memory leaks by implementing correct `DocumentListener` disposal.
- **Marketplace Targeting**: Optimized plugin dependencies to target Android Studio and IntelliJ IDEA specifically.

## [0.0.2-alpha]
### Added
- **Unified Asset Explorer**: Visual management for assets (XML Vectors, SVGs, and Images) across all KMP modules.
- **Native Vector Rendering**: Integrated previewer for Android Vector Drawables (.xml) with zoom controls and real-time refresh.
- **String Resource Manager**: Automatically scan `strings.xml` files and identify missing translations across different locales.
- **K2 Mode Support**: Full compatibility with the new Kotlin K2 mode in Android Studio.
- **Smart Filtering**: Filter assets by type (Vectors, Images, Strings) and by specific KMP modules.
- **Quick Copy**: Single-click to copy resource names or keys directly to the clipboard.
- **Auto-Refresh**: Seamlessly updates the asset list and previews whenever files change in the workspace.
