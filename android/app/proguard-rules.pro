# kotlinx.serialization's JsonElement API is used directly; no reflective DTO adapters.

# Compose saves this enum in the Activity Bundle. Keep its serialized identity
# stable when updating from development builds or between optimized builds.
-keep enum si.hafnar.entalt.ui.Page { *; }
-keep enum si.hafnar.entalt.widget.WidgetActionType { *; }
-keep enum si.hafnar.entalt.domain.LightIcon { *; }
