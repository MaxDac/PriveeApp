"""The fdroid-release skill is shipped for Claude Code and GitHub Copilot; both copies must match."""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
COPIES = [
    ROOT / ".claude/skills/fdroid-release/SKILL.md",
    ROOT / ".github/skills/fdroid-release/SKILL.md",
]


class SkillCopiesTests(unittest.TestCase):
    def test_copies_are_identical(self):
        claude, copilot = (path.read_bytes().replace(b"\r\n", b"\n") for path in COPIES)
        self.assertEqual(
            claude, copilot,
            "Edit .claude/skills/fdroid-release/SKILL.md and copy it to .github/skills/fdroid-release/SKILL.md",
        )

    def test_front_matter_names_the_skill(self):
        text = COPIES[0].read_text(encoding="utf-8")
        self.assertTrue(text.startswith("---\nname: fdroid-release\ndescription: "))


if __name__ == "__main__":
    unittest.main()
