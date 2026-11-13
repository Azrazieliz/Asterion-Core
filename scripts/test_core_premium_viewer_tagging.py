#!/usr/bin/env python3
"""Non-destructive source regressions for the consumer viewer and visual tagging."""
from pathlib import Path

root=Path(__file__).resolve().parents[1]
src=root/"android_app/app/src/main/java/com/ailm/android"
screens=(src/"ui/screens/Screens.kt").read_text()
navigation=(src/"ui/navigation/AppNavHost.kt").read_text()
runtime=(src/"runtime/StandaloneRuntime.kt").read_text()
wide=(src/"runtime/FusionImageWideEvidencePolicy.kt").read_text()
policy=(src/"runtime/SubjectTagConsistencyPolicy.kt").read_text()
gradle=(root/"android_app/app/build.gradle.kts").read_text()

viewer=screens.split("private fun ImageViewerScreen(",1)[1].split("private data class FamilyCoverageRow(",1)[0]
review=screens.split("private fun ReviewSourcePreview(",1)[1].split("private fun ReviewQueueScreen(",1)[0]
organize=screens.split("private fun AiAutomationScreen(",1)[1].split("@Composable",1)[0]

assert "swipeAccumLocal < -threshold && idx >= 0" in viewer
assert "swipeAccumLocal > threshold && idx > 0" in viewer
assert 'Text("Previous")' not in viewer and 'Text("Next")' not in viewer
assert "Swipe the artwork to browse" in viewer
assert "showPersonalLabels" in viewer and "AnimatedVisibility(visible = showPersonalLabels)" in viewer
assert 'RoundedCornerShape(22.dp)' in viewer
assert 'Label("Personal labels (not Fusion attributes)")' not in viewer
assert 'Text("File details"' in viewer

assert 'Text("Analysis details")' not in review # should show actionable hide/show
assert '"Analysis details"' in review
assert "AnimatedVisibility(visible = showTechnical)" in review
assert "Taxonomy coverage" in review
assert "visual tags saved" in review
assert "FlowRow(" in review
assert "No per-family audit recorded yet" not in review

assert "statusBarsPadding()" in navigation
assert "NativeSubjectEvidencePolicy.verifiedCropIds(subjectAnalysis)" in runtime
assert "SubjectTagConsistencyPolicy.reconcile(" in runtime
assert '"withheld_attributes" to cropConsistency.withheld' in runtime
assert "automation_last_visual_tags" in runtime
assert "automation_last_content_ratings" in runtime
assert "nudity_requires_verified_subject_and_strong_evidence" in wide
assert "overlapping_subject_body_type_scores" in policy
assert "weak_or_clothed_nudity_prediction" in policy

assert "lastVisualTags" in screens
assert "Content Rating only · visual tags need attention" in screens
assert "AsterionMetric(" in screens
assert 'versionCode = 20048' in gradle
assert 'versionName = "2.0.48"' in gradle
print("PASS: native visual crop conflict guards, automation receipts, full-viewer swipe and premium UI; phone acceptance still needed")
