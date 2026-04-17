
# Kmp Resources Unfold Changelog

## [0.0.1-alpha]
### Added
- **Unified Asset Explorer**: A dedicated tool window to browse all project assets (XML Vectors, SVGs, and Images) across all KMP modules.
- **Native Vector Rendering**: Integrated previewer for Android Vector Drawables (.xml) with zoom controls and real-time refresh.
- **String Resource Manager**: Automatically scan `strings.xml` files and identify missing translations across different locales.
- **K2 Mode Support**: Full compatibility with the new Kotlin K2 mode in Android Studio Panda (2025.3.3) and Koala (2024.1.x).
- **Smart Filtering**: Filter assets by type (Vectors, Images, Strings) and by specific KMP modules.
- **Quick Copy**: Single-click to copy resource names or keys directly to the clipboard with visual feedback.
- **Auto-Refresh**: Seamlessly updates the asset list and previews whenever files change in the workspace.
- **Internal API Compliance**: Migrated to stable `ImageLoader` and `ImageUtil` APIs for long-term IDE stability.
- **Broad Compatibility**: Validated for Android Studio versions ranging from Koala (241) to Panda (253).
