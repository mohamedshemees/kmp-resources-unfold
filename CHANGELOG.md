<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Kmp Resources Unfold Changelog

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
