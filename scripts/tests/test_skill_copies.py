"""Every skill is shipped for Claude Code and GitHub Copilot; both copies must match."""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
CLAUDE = ROOT / ".claude/skills"
COPILOT = ROOT / ".github/skills"


def skill_files(base: Path) -> set[str]:
    return {path.relative_to(base).as_posix() for path in base.rglob("*") if path.is_file()}


class SkillCopiesTests(unittest.TestCase):
    def test_same_skills_in_both_directories(self):
        self.assertTrue(skill_files(CLAUDE), "no skills found")
        self.assertEqual(
            skill_files(CLAUDE), skill_files(COPILOT),
            "Every file under .claude/skills must exist under .github/skills and vice versa",
        )

    def test_copies_are_identical(self):
        for relative in sorted(skill_files(CLAUDE) & skill_files(COPILOT)):
            with self.subTest(file=relative):
                claude, copilot = (
                    (base / relative).read_bytes().replace(b"\r\n", b"\n") for base in (CLAUDE, COPILOT)
                )
                self.assertEqual(
                    claude, copilot,
                    f"Edit .claude/skills/{relative} and copy it to .github/skills/{relative}",
                )

    def test_front_matter_names_the_skill(self):
        for skill in sorted(path for path in CLAUDE.iterdir() if path.is_dir()):
            with self.subTest(skill=skill.name):
                text = (skill / "SKILL.md").read_text(encoding="utf-8").replace("\r\n", "\n")
                self.assertRegex(skill.name, r"^[a-z0-9]+(-[a-z0-9]+)*$")
                match = re.match(r"---\nname: (\S+)\ndescription: (.+)\n---\n", text)
                self.assertIsNotNone(match, "SKILL.md must start with name/description front matter")
                self.assertEqual(match.group(1), skill.name)
                self.assertGreater(len(match.group(2)), 80, "description should say when to use the skill")

    def test_relative_links_resolve(self):
        for skill_md in sorted(CLAUDE.rglob("SKILL.md")):
            text = skill_md.read_text(encoding="utf-8")
            for target in re.findall(r"\]\((\.\.?/[^)#]+)", text):
                with self.subTest(skill=skill_md.parent.name, link=target):
                    self.assertTrue((skill_md.parent / target).resolve().exists())


if __name__ == "__main__":
    unittest.main()
