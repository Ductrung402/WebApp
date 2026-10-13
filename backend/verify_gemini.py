"""Run the Task 2.1 live proof without creating or changing database records."""

import json
from pathlib import Path
import sys

from app.services.gemini_service import GeminiServiceError, generate_quiz_from_text


def main():
    # Windows console encoding must support Vietnamese output.
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    docs_path = Path(__file__).resolve().parent.parent / "docs"
    raw_text = (docs_path / "gemini_test_input.txt").read_text(encoding="utf-8")
    try:
        questions = generate_quiz_from_text(raw_text, num_questions=1)
    except GeminiServiceError as exc:
        print(json.dumps({
            "error": exc.error_code, "type": type(exc).__name__,
            "status_code": exc.status_code, "message": str(exc),
        }, ensure_ascii=False), file=sys.stderr)
        return 1
    output = json.dumps(questions, ensure_ascii=False, indent=2)
    (docs_path / "gemini_live_output.json").write_text(output + "\n", encoding="utf-8")
    print(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
