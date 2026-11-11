import unittest

from classify_pr import v0


class ClassifyPrTest(unittest.TestCase):
    def test_nested_readme_is_documentation(self):
        self.assertTrue(v0("mai-portfolio-backend/module/README.md", "old", "new"))

    def test_java_comments_do_not_run_application(self):
        old = "class Demo { // 旧说明\n    int count;\n}\n"
        new = "/* 包说明 */\nclass Demo { // 新说明\n    int count;\n}\n"
        self.assertTrue(v0("Demo.java", old, new))
        self.assertFalse(v0("Demo.java", old, new.replace("int count", "long count")))

    def test_package_info_preserves_package_annotations(self):
        self.assertTrue(v0("package-info.java", "/** 旧说明 */\npackage dev.test;\n", None))
        self.assertFalse(v0("package-info.java", "@Deprecated\npackage dev.test;\n", None))

    def test_comment_like_text_in_java_text_block_is_behavior(self):
        old = 'class Demo { String content = """\n// old\n"""; }'
        new = old.replace("// old", "// new")
        self.assertFalse(v0("Demo.java", old, new))

    def test_python_comments_preserve_directives_and_string_contents(self):
        old = 'answer = "# old"\n'
        self.assertTrue(v0("test.py", old, old + "# 中文说明\n"))
        self.assertFalse(v0("test.py", old, old.replace("# old", "# new")))
        self.assertFalse(v0("test.py", old, old.rstrip() + "  # noqa\n"))

    def test_unclassified_source_still_runs_application(self):
        self.assertFalse(v0("Component.vue", "<div>旧</div>", "<div>新</div>"))
        self.assertFalse(v0("route.ts", "const route = /old/", "const route = /new/"))
        self.assertTrue(v0("route.ts", "// 旧\nconst a = 1\n", "// 新\nconst a = 1\n"))
        self.assertFalse(v0("route.ts", "return /* old */ value", "return /*\nnew */ value"))
