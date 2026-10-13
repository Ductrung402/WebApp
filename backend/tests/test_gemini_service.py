"""Offline tests: no Google requests and no database writes."""

import asyncio
import json
import os
import time
from unittest import TestCase, main
from unittest.mock import AsyncMock, MagicMock, patch

from google.genai import errors, types
import httpx

from app.services import gemini_service as service

QUESTION = {
    "noi_dung": "Công thức hóa học của nước là gì?",
    "dap_an_a": "H2O", "dap_an_b": "CO2", "dap_an_c": "O2", "dap_an_d": "NaCl",
    "dap_an_dung": "A",
}
RAW_TEXT = "Nước có công thức hóa học là H2O. Nước sôi ở 100 độ C ở áp suất tiêu chuẩn."


def make_response(text=None, reason=types.FinishReason.STOP):
    return types.GenerateContentResponse(candidates=[types.Candidate(
        finish_reason=reason,
        content=types.Content(parts=[types.Part(text=text if text is not None else json.dumps([QUESTION]))]),
    )])


class GeminiServiceTests(TestCase):
    def setUp(self):
        self.env_patch = patch.dict(os.environ, {
            "GEMINI_API_KEY": "offline-test-key", "GOOGLE_API_KEY": "", "GEMINI_MODEL": "offline-test-model",
        })
        self.env_patch.start()
        self.addCleanup(self.env_patch.stop)
        self.file_patch = patch.object(service, "dotenv_values", return_value={})
        self.file_patch.start()
        self.addCleanup(self.file_patch.stop)

    def make_client(self, response=None, error=None):
        ai = MagicMock()
        ai.__aenter__ = AsyncMock(return_value=ai)
        ai.__aexit__ = AsyncMock(return_value=False)
        ai.models.generate_content = AsyncMock(return_value=response or make_response(), side_effect=error)
        return MagicMock(aio=ai), ai

    def test_success_contract_and_structured_output_configuration(self):
        client, ai = self.make_client()
        with patch.object(service.genai, "Client", return_value=client) as constructor:
            result = service.generate_quiz_from_text(RAW_TEXT, 1)
        self.assertEqual(result, [QUESTION])
        self.assertEqual(set(result[0]), set(QUESTION))
        options = constructor.call_args.kwargs["http_options"]
        self.assertEqual(options.timeout, 15000)
        self.assertEqual(options.retry_options.attempts, 1)
        args = ai.models.generate_content.call_args.kwargs
        self.assertEqual(args["model"], "offline-test-model")
        self.assertEqual(json.loads(args["contents"]), {"num_questions": 1, "source_text": RAW_TEXT})
        config = args["config"]
        self.assertEqual(config.response_mime_type, "application/json")
        self.assertEqual(config.response_json_schema["type"], "array")
        self.assertEqual(config.response_json_schema["minItems"], 1)
        self.assertEqual(config.response_json_schema["maxItems"], 1)
        self.assertEqual(set(config.response_json_schema["items"]["required"]), set(QUESTION))
        self.assertEqual(config.system_instruction, service.SYSTEM_PROMPT)
        ai.__aexit__.assert_awaited_once()
        client.close.assert_called_once()

    def test_default_question_count_is_ten(self):
        with patch.object(service, "_request_quiz", AsyncMock(return_value=make_response(json.dumps([QUESTION] * 10)))) as request:
            self.assertEqual(len(service.generate_quiz_from_text(RAW_TEXT)), 10)
        self.assertEqual(request.call_args.args[1], 10)

    def test_valid_values_are_trimmed_and_answer_labels_remain_strict(self):
        question = {**QUESTION, "noi_dung": "  " + QUESTION["noi_dung"] + "  "}
        self.assertEqual(service.validate_quiz_json(json.dumps([question]), 1), [QUESTION])

    def test_invalid_json_top_level_and_duplicate_keys_are_rejected(self):
        invalid = ["", "not JSON", "```json\n[]\n```", "[", json.dumps(QUESTION), "null", "[]", '{"questions": []}', '[{"noi_dung":"a","noi_dung":"b"}]']
        for text in invalid:
            with self.subTest(text=text), self.assertRaises(service.GeminiResponseError) as result:
                service.validate_quiz_json(text, 1)
            self.assertEqual(result.exception.status_code, 400)
            self.assertEqual(result.exception.error_code, "ERR_01")

    def test_all_missing_and_extra_fields_are_rejected(self):
        for key in QUESTION:
            invalid = {name: value for name, value in QUESTION.items() if name != key}
            with self.subTest(missing=key), self.assertRaises(service.GeminiResponseError):
                service.validate_quiz_json(json.dumps([invalid]), 1)
        for extra in ["id_cau_hoi", "id_de_thi", "loi_giai"]:
            with self.subTest(extra=extra), self.assertRaises(service.GeminiResponseError):
                service.validate_quiz_json(json.dumps([{**QUESTION, extra: "forbidden"}]), 1)

    def test_non_string_blank_and_invalid_answer_fields_are_rejected(self):
        for key in QUESTION:
            for value in [None, 123, True, "", "   ", ["A"]]:
                with self.subTest(key=key, value=value), self.assertRaises(service.GeminiResponseError):
                    service.validate_quiz_json(json.dumps([{**QUESTION, key: value}]), 1)
        for answer in ["a", "E", "AB", " A "]:
            with self.subTest(answer=answer), self.assertRaises(service.GeminiResponseError):
                service.validate_quiz_json(json.dumps([{**QUESTION, "dap_an_dung": answer}]), 1)

    def test_one_invalid_item_rejects_the_entire_array(self):
        with self.assertRaises(service.GeminiResponseError):
            service.validate_quiz_json(json.dumps([QUESTION, {**QUESTION, "dap_an_dung": "E"}]), 2)

    def test_duplicate_options_and_incorrect_count_are_rejected(self):
        with self.assertRaises(service.GeminiResponseError):
            service.validate_quiz_json(json.dumps([{**QUESTION, "dap_an_b": " h2o "}]), 1)
        with self.assertRaises(service.GeminiResponseError):
            service.validate_quiz_json(json.dumps([QUESTION]), 2)

    def test_invalid_input_is_rejected_before_creating_client(self):
        with patch.object(service.genai, "Client") as client:
            for text in [None, 123, "", "   "]:
                with self.subTest(text=text), self.assertRaises(service.GeminiInputError):
                    service.generate_quiz_from_text(text)
            for count in [True, False, 0, -1, 1.5, "1", None]:
                with self.subTest(count=count), self.assertRaises(service.GeminiInputError):
                    service.generate_quiz_from_text(RAW_TEXT, count)
            client.assert_not_called()

    def test_missing_key_does_not_create_client(self):
        with patch.dict(os.environ, {"GEMINI_API_KEY": "", "GOOGLE_API_KEY": ""}), patch.object(service.genai, "Client") as client:
            with self.assertRaises(service.GeminiServiceError) as result:
                service.generate_quiz_from_text(RAW_TEXT)
            self.assertEqual(result.exception.status_code, 503)
            client.assert_not_called()

    def test_google_key_fallback_and_dotenv_are_supported(self):
        with patch.dict(os.environ, {"GEMINI_API_KEY": "", "GOOGLE_API_KEY": "", "GEMINI_MODEL": ""}), patch.object(service, "dotenv_values", return_value={"GOOGLE_API_KEY": "file-test-key", "GEMINI_MODEL": "file-test-model"}), patch.object(service, "_request_quiz", AsyncMock(return_value=make_response())) as request:
            service.generate_quiz_from_text(RAW_TEXT, 1)
            self.assertEqual(request.call_args.args[2:], ("file-test-key", "file-test-model"))

    def test_real_fifteen_second_deadline_cancels_request_and_closes_clients(self):
        cancelled = []
        async def slow_generation(**kwargs):
            try:
                await asyncio.sleep(60)
            finally:
                cancelled.append(True)
        client, ai = self.make_client()
        ai.models.generate_content.side_effect = slow_generation
        started = time.monotonic()
        with patch.object(service.genai, "Client", return_value=client):
            with self.assertRaises(service.GeminiTimeoutError) as result:
                service.generate_quiz_from_text(RAW_TEXT, 1)
        elapsed = time.monotonic() - started
        self.assertGreaterEqual(elapsed, 14.8)
        self.assertLess(elapsed, 16.5)
        self.assertEqual(service.TIMEOUT_SECONDS, 15)
        self.assertEqual(result.exception.status_code, 503)
        self.assertEqual(cancelled, [True])
        ai.__aexit__.assert_awaited_once()
        client.close.assert_called_once()

    def test_transport_and_api_failures_raise_sanitized_service_errors(self):
        failures = [
            (httpx.ReadTimeout("private provider detail"), service.GeminiTimeoutError),
            (httpx.ConnectError("private provider detail"), service.GeminiServiceError),
            (errors.ClientError(429, {"error": {"message": "private provider detail"}}), service.GeminiServiceError),
            (errors.ServerError(504, {"error": {"message": "private provider detail"}}), service.GeminiTimeoutError),
        ]
        for error, expected in failures:
            with self.subTest(error=type(error).__name__), patch.object(service, "_request_quiz", AsyncMock(side_effect=error)):
                with self.assertRaises(expected) as result:
                    service.generate_quiz_from_text(RAW_TEXT, 1)
                self.assertEqual(result.exception.status_code, 503)
                self.assertNotIn("private provider detail", str(result.exception))

    def test_empty_blocked_or_truncated_provider_response_is_rejected(self):
        responses = [
            types.GenerateContentResponse(),
            make_response(reason=types.FinishReason.MAX_TOKENS),
            make_response(reason=types.FinishReason.SAFETY),
            make_response(text=""),
        ]
        for response in responses:
            with self.subTest(response=response), patch.object(service, "_request_quiz", AsyncMock(return_value=response)):
                with self.assertRaises(service.GeminiResponseError):
                    service.generate_quiz_from_text(RAW_TEXT, 1)

    def test_invalid_json_raises_through_public_function(self):
        with patch.object(service, "_request_quiz", AsyncMock(return_value=make_response("not JSON"))):
            with self.assertRaises(service.GeminiResponseError):
                service.generate_quiz_from_text(RAW_TEXT, 1)

    def test_sync_service_requires_to_thread_when_called_from_event_loop(self):
        async def direct_call():
            with self.assertRaises(service.GeminiServiceError):
                service.generate_quiz_from_text(RAW_TEXT, 1)
        asyncio.run(direct_call())


if __name__ == "__main__":
    main()
