import unittest

from post_review import anchorable_lines, partition, render_body

TWO_HUNKS = """diff --git a/src/first/robot/Drive.java b/src/first/robot/Drive.java
index 1111111..2222222 100644
--- a/src/first/robot/Drive.java
+++ b/src/first/robot/Drive.java
@@ -10,5 +10,6 @@ class Drive {
 context ten
 context eleven
-removed twelve
+added twelve
+added thirteen
 context fourteen
 context fifteen
@@ -40,2 +41,3 @@ class Drive {
 context forty one
+added forty two
 context forty three
"""

NEW_FILE = """diff --git a/a.txt b/a.txt
new file mode 100644
index 0000000..3333333
--- /dev/null
+++ b/a.txt
@@ -0,0 +1,2 @@
+one
+two
"""

DELETED_FILE = """diff --git a/gone.txt b/gone.txt
deleted file mode 100644
index 3333333..0000000
--- a/gone.txt
+++ /dev/null
@@ -1,2 +0,0 @@
-one
-two
"""


class AnchorableLines(unittest.TestCase):
    def test_context_and_added_lines_anchor_removed_lines_do_not(self):
        anchors = anchorable_lines(TWO_HUNKS)
        self.assertEqual(
            anchors["src/first/robot/Drive.java"],
            {10, 11, 12, 13, 14, 15, 41, 42, 43},
        )

    def test_a_new_file_anchors_every_line(self):
        self.assertEqual(anchorable_lines(NEW_FILE), {"a.txt": {1, 2}})

    def test_a_deleted_file_anchors_nothing(self):
        self.assertEqual(anchorable_lines(DELETED_FILE), {})

    def test_a_no_newline_marker_does_not_advance_the_counter(self):
        diff = NEW_FILE + "\\ No newline at end of file\n"
        self.assertEqual(anchorable_lines(diff), {"a.txt": {1, 2}})

    def test_an_added_line_that_looks_like_a_file_header(self):
        # `git diff` writes an added line "++ x" as "+++ x", which is a file header everywhere
        # except inside a hunk.
        diff = (
            "diff --git a/m.md b/m.md\n"
            "--- a/m.md\n"
            "+++ b/m.md\n"
            "@@ -1,1 +1,3 @@\n"
            " one\n"
            "+++ b/not-a-header\n"
            "+--- a/not-a-header\n"
        )
        self.assertEqual(anchorable_lines(diff), {"m.md": {1, 2, 3}})

    def test_several_files_in_one_diff(self):
        anchors = anchorable_lines(TWO_HUNKS + NEW_FILE)
        self.assertEqual(sorted(anchors), ["a.txt", "src/first/robot/Drive.java"])


class Partition(unittest.TestCase):
    anchors = {"a.txt": {1, 2}}

    def test_a_finding_on_a_diff_line_is_inline(self):
        inline, orphans = partition(
            [{"path": "a.txt", "line": 2, "body": "b"}], self.anchors
        )
        self.assertEqual(inline, [{"path": "a.txt", "line": 2, "side": "RIGHT", "body": "b"}])
        self.assertEqual(orphans, [])

    def test_a_finding_off_the_diff_is_an_orphan(self):
        inline, orphans = partition(
            [{"path": "a.txt", "line": 9, "body": "b"}], self.anchors
        )
        self.assertEqual(inline, [])
        self.assertEqual(len(orphans), 1)

    def test_a_finding_on_an_untouched_file_is_an_orphan(self):
        inline, orphans = partition(
            [{"path": "other.txt", "line": 1, "body": "b"}], self.anchors
        )
        self.assertEqual(inline, [])
        self.assertEqual(len(orphans), 1)

    def test_a_leading_slash_or_b_prefix_is_tolerated(self):
        inline, _ = partition([{"path": "b/a.txt", "line": 1, "body": "b"}], self.anchors)
        self.assertEqual(inline[0]["path"], "a.txt")

    def test_a_finding_with_no_line_is_an_orphan_not_a_crash(self):
        inline, orphans = partition([{"path": "a.txt", "body": "b"}], self.anchors)
        self.assertEqual(inline, [])
        self.assertEqual(len(orphans), 1)

    def test_a_finding_that_is_not_an_object_is_dropped(self):
        inline, orphans = partition(["nonsense"], self.anchors)
        self.assertEqual((inline, orphans), ([], []))


class RenderBody(unittest.TestCase):
    def test_orphans_are_carried_into_the_summary(self):
        body = render_body("all clear", [{"path": "x.java", "line": 7, "body": "hazard"}])
        self.assertIn("all clear", body)
        self.assertIn("x.java:7", body)
        self.assertIn("hazard", body)

    def test_no_orphans_leaves_the_summary_alone(self):
        body = render_body("all clear", [])
        self.assertIn("all clear", body)
        self.assertNotIn("outside the diff", body)


if __name__ == "__main__":
    unittest.main()
